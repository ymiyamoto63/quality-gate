package com.qualitygate;

import com.qualitygate.config.GateConfigService;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.RunSkippedMetric;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.evaluate.GateThresholds;
import com.qualitygate.evaluate.RunEvaluationService;
import com.qualitygate.normalize.ReportNormalizer;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.platform.storage.StoredArtifact;
import com.qualitygate.query.TrendQueryService;
import com.qualitygate.query.dto.TrendResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 指標の時系列（S-05）を検証する。
 *
 * <p>本物の判定パイプラインで Run を積んでから読む。作り置きの行を流し込むと、
 * 判定側が書いた形と参照側が読む形の食い違いを見逃す。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AbstractIntegrationTest
class TrendApiIT {

    private static final String TRIVY_CLEAN = """
            { "version": "2.1.0", "runs": [{
              "tool": { "driver": { "name": "Trivy", "rules": [] }}, "results": []
            }]}
            """;

    private static final String PMD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <pmd version="7.0.0">
              <file name="/build/backend/src/main/java/com/qualitygate/Good.java">
                <violation beginline="42" rule="CyclomaticComplexity" method="big">
            The method 'big()' has a cyclomatic complexity of 20.
                </violation>
              </file>
            </pmd>
            """;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired RunSkippedMetricRepository skippedMetrics;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired MeasurementRepository measurements;
    @Autowired FindingRepository findings;
    @Autowired RepositorySummaryRepository summaries;
    @Autowired GateConfigRepository gateConfigs;
    @Autowired ArtifactStore artifactStore;
    @Autowired ReportNormalizer normalizer;
    @Autowired RunEvaluationService evaluationService;
    @Autowired GateConfigService gateConfigService;
    @Autowired TrendQueryService trendService;
    @Autowired WebApplicationContext webApplicationContext;

    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        findings.deleteAll();
        measurements.deleteAll();
        artifacts.deleteAll();
        skippedMetrics.deleteAll();
        summaries.deleteAll();
        runs.deleteAll();
        gateConfigs.deleteAll();
        repositories.deleteAll();
        users.deleteAll();

        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(),
                "ymiyamoto63", "quality-gate", admin.getId())).getId();
    }

    @Test
    void カバレッジの推移を古い順に返す() {
        evaluated(Instant.parse("2026-09-20T00:00:00Z"), "main", 17);
        evaluated(Instant.parse("2026-09-21T00:00:00Z"), "main", 16);
        evaluated(Instant.parse("2026-09-22T00:00:00Z"), "main", 19);

        TrendResponse trend = trend("M-01");

        assertThat(trend.name()).isEqualTo("ブランチカバレッジ");
        assertThat(trend.unit()).isEqualTo("percent");
        assertThat(trend.threshold()).containsEntry("operator", ">=");
        assertThat(trend.series()).singleElement().satisfies(series -> {
            assertThat(series.componentName()).isEqualTo("backend");
            assertThat(series.judged()).isTrue();
            assertThat(series.points()).extracting(TrendResponse.TrendPoint::measuredAt)
                    .containsExactly(
                            Instant.parse("2026-09-20T00:00:00Z"),
                            Instant.parse("2026-09-21T00:00:00Z"),
                            Instant.parse("2026-09-22T00:00:00Z"));
            assertThat(series.points()).extracting(TrendResponse.TrendPoint::value)
                    .containsExactly(new BigDecimal("85.0000"), new BigDecimal("80.0000"),
                            new BigDecimal("95.0000"));
        });
    }

    /**
     * 未計測を 0 で埋めない。0 を打つと、グラフ上では
     * 「カバレッジが 0% まで落ちた」という最悪の値として読めてしまう。
     */
    @Test
    void 未計測は値なしの点として返す() {
        evaluated(Instant.parse("2026-09-20T00:00:00Z"), "main", 17);
        skipped(Instant.parse("2026-09-21T00:00:00Z"));

        TrendResponse trend = trend("M-01");

        assertThat(trend.series()).singleElement().satisfies(series ->
                assertThat(series.points()).satisfiesExactly(
                        first -> assertThat(first.value()).isNotNull(),
                        second -> {
                            assertThat(second.status()).isEqualTo(MeasurementStatus.SKIP);
                            assertThat(second.value()).isNull();
                        }));
    }

    /**
     * スキップと計測エラーは指標ごと Run ごとに起きるため、判定結果に
     * コンポーネント名が付かない。これを別系列にすると、欠測が
     * 「もう 1 本の線」としてグラフに現れる。
     */
    @Test
    void 未計測はコンポーネント別の系列の欠測として現れる() {
        twoComponents(Instant.parse("2026-09-20T00:00:00Z"));
        skipped(Instant.parse("2026-09-21T00:00:00Z"));
        twoComponents(Instant.parse("2026-09-22T00:00:00Z"));

        TrendResponse trend = trend("M-01");

        assertThat(trend.series()).hasSize(2)
                .extracting(TrendResponse.TrendSeries::componentName)
                .containsExactly("backend", "frontend");
        // どちらの系列にも同じ日付の欠測が入る
        assertThat(trend.series()).allSatisfy(series ->
                assertThat(series.points()).satisfiesExactly(
                        first -> assertThat(first.value()).isNotNull(),
                        gap -> {
                            assertThat(gap.measuredAt())
                                    .isEqualTo(Instant.parse("2026-09-21T00:00:00Z"));
                            assertThat(gap.value()).isNull();
                        },
                        last -> assertThat(last.value()).isNotNull()));
    }

    @Test
    void コンポーネントごとに系列を分ける() {
        twoComponents(Instant.parse("2026-09-22T00:00:00Z"));

        TrendResponse trend = trend("M-01");

        // backend と frontend を 1 本の線にしても意味がない
        assertThat(trend.series()).hasSize(2)
                .extracting(TrendResponse.TrendSeries::componentName)
                .containsExactly("backend", "frontend");
        // 色は系列の同一性に固定する。並び順で振ると絞り込みで塗り替わる
        assertThat(trend.series()).extracting(TrendResponse.TrendSeries::colorIndex)
                .containsExactly(0, 1);
    }

    @Test
    void 別ブランチのRunは混ざらない() {
        evaluated(Instant.parse("2026-09-21T00:00:00Z"), "main", 17);
        evaluated(Instant.parse("2026-09-22T00:00:00Z"), "feature/x", 17);

        assertThat(trend("M-01").series().getFirst().points()).hasSize(1);
        assertThat(trendOf("M-01", "feature/x").series().getFirst().points()).hasSize(1);
    }

    /**
     * 変更範囲だけの値と全量の値を 1 本の線で結ぶと、範囲が切り替わるたびに
     * 品質が乱高下して見える。範囲ごとに別の系列にする。
     */
    @Test
    void ミューテーションスコアは実行範囲ごとに系列を分け対象外は系列にしない() {
        mutation(Instant.parse("2026-09-20T00:00:00Z"), 8, "changed");
        mutation(Instant.parse("2026-09-21T00:00:00Z"), 9, "all");
        mutation(Instant.parse("2026-09-22T00:00:00Z"), 7, "changed");

        TrendResponse trend = trend("M-02");

        // frontend は対象外（NOT_APPLICABLE）。値の無い線を 1 本増やさない
        assertThat(trend.series())
                .extracting(TrendResponse.TrendSeries::label)
                .containsExactly("backend（全量）", "backend（変更範囲）");
        assertThat(trend.series().get(1).points())
                .extracting(TrendResponse.TrendPoint::value)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(new java.math.BigDecimal("80"), new java.math.BigDecimal("70"));
    }

    /** 実行範囲の分からない計測エラーは、新しい系列ではなく既存の系列の欠測にする。 */
    @Test
    void 実行範囲の分からない計測エラーは既存の系列の欠測になる() {
        mutation(Instant.parse("2026-09-20T00:00:00Z"), 8, "changed");
        mutation(Instant.parse("2026-09-21T00:00:00Z"), 8, "changed", "all");
        mutation(Instant.parse("2026-09-22T00:00:00Z"), 8, "changed");

        TrendResponse trend = trend("M-02");

        assertThat(trend.series()).singleElement().satisfies(series -> {
            assertThat(series.label()).isEqualTo("backend（変更範囲）");
            assertThat(series.points()).extracting(TrendResponse.TrendPoint::status)
                    .containsExactly(MeasurementStatus.PASS, MeasurementStatus.ERROR,
                            MeasurementStatus.PASS);
        });
    }

    /** 既定ブランチはリポジトリの設定に従う。画面が "main" を決め打ちしない。 */
    @Test
    void ブランチ省略時はリポジトリの既定ブランチを使う() {
        evaluated(Instant.parse("2026-09-22T00:00:00Z"), "main", 17);

        assertThat(trend("M-01").branch()).isEqualTo("main");
    }

    /**
     * 処理に失敗した Run を含めない。含めると、品質の変化ではなく
     * 計測基盤の不調がグラフに現れる。
     */
    @Test
    void 判定できていないRunは含めない() {
        evaluated(Instant.parse("2026-09-21T00:00:00Z"), "main", 17);
        Run failed = createRun(Instant.parse("2026-09-22T00:00:00Z"), "main");
        failed.markFailed("CONFIG_VALIDATION_FAILED", "設定が不正");
        runs.save(failed);

        assertThat(trend("M-01").series().getFirst().points()).hasSize(1);
    }

    @Test
    void 期間外のRunは含めない() {
        evaluated(Instant.parse("2026-06-01T00:00:00Z"), "main", 17);
        evaluated(Instant.parse("2026-09-22T00:00:00Z"), "main", 17);

        TrendResponse recent = trendService.trend(repositoryId, "M-01", "main",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));

        assertThat(recent.series().getFirst().points()).hasSize(1);
    }

    /**
     * しきい値は設定で変えられるため、期間内で一定とは限らない。
     * 1 本の線だけ引くと、過去の点が当時とは違う基準で判定されたように見える。
     */
    @Test
    void 期間内でしきい値が変わったことを示す() {
        evaluatedWithThreshold(Instant.parse("2026-09-21T00:00:00Z"), 70);
        evaluatedWithThreshold(Instant.parse("2026-09-22T00:00:00Z"), 90);

        TrendResponse trend = trend("M-01");

        assertThat(trend.thresholdChanged()).isTrue();
        // 線は現在の基準として引く
        assertThat(trend.threshold()).containsEntry("value", 90);
    }

    @Test
    void しきい値が変わっていなければ変更なしとする() {
        evaluated(Instant.parse("2026-09-21T00:00:00Z"), "main", 17);
        evaluated(Instant.parse("2026-09-22T00:00:00Z"), "main", 16);

        assertThat(trend("M-01").thresholdChanged()).isFalse();
    }

    @Test
    void 計測がなければ空の系列を返す() {
        TrendResponse trend = trend("M-01");

        // 「まだ計測がない」と「取得に失敗した」は別物なので、エラーにしない
        assertThat(trend.series()).isEmpty();
        assertThat(trend.threshold()).isNull();
        assertThat(trend.unit()).isNull();
    }

    @Test
    void 存在しないリポジトリは404で返す() {
        UUID unknown = Uuid7.generate();

        assertThatThrownBy(() -> trendService.trend(unknown, "M-01", "main", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(unknown.toString());
    }

    /**
     * 実際の JSON まで通し、画面の検査に使う応答例を書き出す。
     *
     * <p>手で書いた例を置くと、API が変わっても検査は通り続け、実際の画面だけが壊れる。
     * {@code api/openapi.yml} と同じく実物から生成する（{@link FixtureWriter}）。
     */
    @Test
    void 応答のJSONが画面の読む形になっている() throws Exception {
        // 欠測・しきい値変更・値の増減を含む、実際に起こる形の系列を作る。
        // 値が一定だと、線の傾きや不合格のマーカーが検査も目視も素通りする。
        twoComponents(Instant.parse("2026-09-08T00:00:00Z"), 18);
        twoComponents(Instant.parse("2026-09-10T00:00:00Z"), 16);
        skipped(Instant.parse("2026-09-12T00:00:00Z"));
        twoComponents(Instant.parse("2026-09-14T00:00:00Z"), 13);
        twoComponents(Instant.parse("2026-09-16T00:00:00Z"), 19);
        evaluatedWithThreshold(Instant.parse("2026-09-18T00:00:00Z"), 90);

        MockMvcTester tester = MockMvcTester.create(
                MockMvcBuilders.webAppContextSetup(webApplicationContext)
                        .apply(org.springframework.security.test.web.servlet.setup
                                .SecurityMockMvcConfigurers.springSecurity())
                        .defaultRequest(org.springframework.test.web.servlet.request
                                .MockMvcRequestBuilders.get("/")
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.user("viewer")))
                        .build());

        var response = tester.get()
                .uri("/api/v1/repositories/{id}/trends?metricId=M-01"
                        + "&from=2026-09-01T00:00:00Z&to=2026-09-30T00:00:00Z", repositoryId)
                .exchange();

        assertThat(response).hasStatusOk().bodyJson().satisfies(content -> {
            var json = content.assertThat();
            json.extractingPath("$.metricId").isEqualTo("M-01");
            json.extractingPath("$.thresholdChanged").isEqualTo(true);
            // 未計測は 0 ではなく null。0 を返すとグラフ上で最悪の値として読める
            json.extractingPath("$.series[0].points[?(@.status == 'SKIP')].value")
                    .asArray().containsExactly((Object) null);
            // 色は系列ごとに固定して返す
            json.extractingPath("$.series[0].colorIndex").asNumber().isEqualTo(0);
            json.extractingPath("$.series[1].colorIndex").asNumber().isEqualTo(1);
        });

        FixtureWriter.write("trend.json", response.getResponse().getContentAsString());
    }

    private TrendResponse trend(String metricId) {
        return trendOf(metricId, null);
    }

    private TrendResponse trendOf(String metricId, String branch) {
        return trendService.trend(repositoryId, metricId, branch,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"));
    }

    private static String jacoco(int covered) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="quality-gate">
                  <package name="com/qualitygate">
                    <class name="com/qualitygate/Good" sourcefilename="Good.java">
                      <counter type="BRANCH" missed="%d" covered="%d"/>
                    </class>
                  </package>
                </report>
                """.formatted(20 - covered, covered);
    }

    private void evaluated(Instant measuredAt, String branch, int covered) {
        Run run = createRun(measuredAt, branch);
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", jacoco(covered));
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, TRIVY_CLEAN);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD);
        evaluate(run);
    }

    private void evaluatedWithThreshold(Instant measuredAt, int threshold) {
        Run run = createRun(measuredAt, "main");
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, """
                version: 1
                metrics:
                  branch_coverage:
                    threshold: %d
                  vulnerabilities:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                """.formatted(threshold));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", jacoco(17));
        evaluate(run);
    }

    /** backend（JaCoCo）と frontend（lcov）の両方を計測した Run。 */
    private void twoComponents(Instant measuredAt) {
        twoComponents(measuredAt, 17);
    }

    private void twoComponents(Instant measuredAt, int covered) {
        Run run = createRun(measuredAt, "main");
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", jacoco(covered));
        attach(run, ArtifactType.LCOV, "lcov.info", "frontend", """
                TN:
                SF:src/api/client.ts
                BRF:20
                BRH:%d
                end_of_record
                """.formatted(Math.max(0, covered - 3)));
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, TRIVY_CLEAN);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD);
        evaluate(run);
    }

    /**
     * backend の PIT と frontend の lcov を計測した Run。
     * 実行範囲を複数渡すと、範囲の違う成果物が混在した Run になる。
     */
    private void mutation(Instant measuredAt, int killed, String... scopes) {
        Run run = createRun(measuredAt, "main");
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, """
                version: 1
                metrics:
                  mutation_score:
                    components: [backend]
                  vulnerabilities:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                """);
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", jacoco(17));
        attach(run, ArtifactType.LCOV, "lcov.info", "frontend",
                "SF:src/api/client.ts\nBRF:20\nBRH:18\nend_of_record\n");
        for (String scope : scopes) {
            String xml = "<mutations>"
                    + mutant("KILLED").repeat(killed)
                    + mutant("SURVIVED").repeat(10 - killed)
                    + "</mutations>";
            attach(run, ArtifactType.PIT_XML, "mutations-" + scope + ".xml", "backend", xml,
                    "{\"mutationScope\":\"%s\"}".formatted(scope));
        }
        evaluate(run);
    }

    private static String mutant(String status) {
        return "<mutation status='%s'><sourceFile>Good.java</sourceFile>".formatted(status)
                + "<mutatedClass>com.qualitygate.Good</mutatedClass></mutation>";
    }

    private void skipped(Instant measuredAt) {
        Run run = createRun(measuredAt, "main");
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, """
                version: 1
                execution:
                  skippable_metrics: [branch_coverage]
                metrics:
                  vulnerabilities:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                """);
        skippedMetrics.save(new RunSkippedMetric(run.getId(), "M-01",
                "PR の計測では実行しない"));
        evaluate(run);
    }

    private void evaluate(Run run) {
        List<ArtifactRecord> records = artifacts.findByRunId(run.getId());
        GateConfigService.Resolved config = gateConfigService.resolve(run, records);
        GateThresholds thresholds = GateThresholds.from(config.document());
        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions());
        evaluationService.evaluate(run.getId(), input, thresholds,
                config.isDefault() ? null : config.gateConfig().getId());
    }

    private Run createRun(Instant measuredAt, String branch) {
        String commitSha = String.format("%040x",
                Math.abs((measuredAt.toString() + branch).hashCode()));
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, branch,
                "github-actions", measuredAt,
                runs.findMaxAttempt(repositoryId, commitSha) + 1);
        run.finalizeIngest();
        return runs.save(run);
    }

    private void attach(Run run, ArtifactType type, String filename, String component,
                        String content) {
        attach(run, type, filename, component, content, null);
    }

    private void attach(Run run, ArtifactType type, String filename, String component,
                        String content, String metadata) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(),
                component, null, metadata));
    }
}
