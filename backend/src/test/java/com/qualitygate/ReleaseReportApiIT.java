package com.qualitygate;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.evaluate.GateThresholds;
import com.qualitygate.evaluate.RunEvaluationService;
import com.qualitygate.normalize.ReportNormalizer;
import com.qualitygate.platform.config.QualityGateProperties;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.platform.storage.StoredArtifact;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** リリース判定と履歴。本物の判定パイプラインで Run を積んでから読む。 */
@SpringBootTest
@AbstractIntegrationTest
class ReleaseReportApiIT {

    /** M-01（合格ライン 80%。既定より厳しい）と M-06（関数ごとの上限 18。既定 15 より緩い）だけを判定する。 */
    private static final QualityGateProperties.Gate GATE = new QualityGateProperties.Gate(
            GateThresholds.ALL_METRICS.stream().filter(id -> !List.of("M-01", "M-06").contains(id)).toList(),
            List.of("**/generated/**"), new BigDecimal("80"), null, null, null, null, null, null, null, null,
            18, null, null, null, null, null, null, null, null);

    /** 判定の対象外にした指標（M-01 / M-06 以外）を並べた、結論の理由の後半。 */
    private static final String NOT_JUDGED = "ミューテーションスコア、応答時間 p95、エラー率、重大・高 脆弱性件数、破壊的変更件数、"
            + "アクセシビリティ違反、テスト成功率、スキップされたテスト数、シークレット検出件数、ライセンス違反件数";

    /** 分岐 20 のうち 17 を通った JaCoCo の結果（85%）。 */
    private static final String JACOCO_85 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="quality-gate">
              <package name="com/qualitygate">
                <class name="com/qualitygate/Good" sourcefilename="Good.java">
                  <counter type="BRANCH" missed="3" covered="17"/>
                </class>
              </package>
            </report>
            """;

    private static final String PASSING = "1".repeat(40);
    private static final String FAILING = "2".repeat(40);
    private static final String REMEASURED = "4".repeat(40);

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired ArtifactStore artifactStore;
    @Autowired ReportNormalizer normalizer;
    @Autowired RunEvaluationService evaluationService;
    @Autowired WebApplicationContext context;

    private UUID repositoryId;
    private MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(),
                "ymiyamoto63", "quality-gate")).getId();
        mvc = MockMvcTester.create(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                        .with(user(IntegrationCleanup.LOGIN_USERNAME)))
                .build());

        evaluated(PASSING, "2026-09-20T03:00:00Z", 17, 8);       // 85%・複雑な関数なし → 合格
        evaluated(FAILING, "2026-09-21T03:00:00Z", 15, 20);      // 75%・複雑度 20 の関数 → 不合格
        evaluated(REMEASURED, "2026-09-23T03:00:00Z", 15, 8);    // 1 回目は不合格
        evaluated(REMEASURED, "2026-09-24T03:00:00Z", 17, 8);    // 直して計測し直すと合格
    }

    @Test
    void すべて合格ならリリース可() {
        // 短い SHA は、計測済みのコミットから完全な SHA にする
        assertThat(mvc.get().uri("/api/v1/release?ref=1111111"))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.repositoryFullName").isEqualTo("ymiyamoto63/quality-gate");
                    json.extractingPath("$.commitSha").isEqualTo(PASSING);
                    json.extractingPath("$.commitUrl")
                            .isEqualTo("https://github.com/ymiyamoto63/quality-gate/commit/" + PASSING);
                    json.extractingPath("$.decision").isEqualTo("RELEASABLE");
                    // 判定から外した指標を結論の理由に必ず添える（「すべて合格」だけでは判定の範囲が分からない）
                    json.extractingPath("$.decisionReason")
                            .isEqualTo("判定した 2 件の指標が、すべて合格ラインを満たしています（10 件は判定の対象外です: "
                                    + NOT_JUDGED + "）。");
                    json.extractingPath("$.notJudged").asArray().hasSize(10);
                    json.extractingPath("$.notJudged[0].metricId").isEqualTo("M-02");
                    json.extractingPath("$.notJudged[0].componentName").isNull();
                    json.extractingPath("$.notJudged[0].reason").isEqualTo("設定（QG_DISABLED_METRICS）で判定から外しています");
                    json.extractingPath("$.counts.judged").asNumber().isEqualTo(2);
                    json.extractingPath("$.metrics[0].metricId").isEqualTo("M-01");
                    json.extractingPath("$.metrics[0].threshold").isEqualTo("≥ 80%");
                    // 既定（75%）より厳しい合格ラインは「緩い」と示さない
                    json.extractingPath("$.metrics[0].thresholdRelaxed").isEqualTo(false);
                    json.extractingPath("$.metrics[0].defaultThreshold").isNull();
                    // QG_EXCLUSIONS で外したファイル（generated の 1 クラス）
                    json.extractingPath("$.metrics[0].excludedFileCount").asNumber().isEqualTo(1);
                    json.extractingPath("$.metrics[0].findings").asArray().isEmpty();
                    // 関数ごとの上限を既定の 15 から 18 に緩めて判定した
                    json.extractingPath("$.metrics[1].metricId").isEqualTo("M-06");
                    json.extractingPath("$.metrics[1].thresholdRelaxed").isEqualTo(true);
                    json.extractingPath("$.metrics[1].defaultThreshold").isEqualTo("関数ごとの循環的複雑度の上限 15");
                    json.extractingPath("$.guides[0].basisLabel").isEqualTo("業界の目安");
                    json.extractingPath("$.guides[0].summary").asString().contains("分かれ道");
                });
    }

    @Test
    void 不合格があればリリース不可で不合格の指標と主な違反を示す() throws Exception {
        var response = mvc.get().uri("/api/v1/release?ref={ref}", FAILING).exchange();

        assertThat(response).hasStatusOk().bodyJson().satisfies(content -> {
            var json = content.assertThat();
            json.extractingPath("$.decision").isEqualTo("NOT_RELEASABLE");
            json.extractingPath("$.decisionReason").isEqualTo(
                    "2 件の指標が合格ラインを満たしていません（ブランチカバレッジ、循環的複雑度が上限を超える関数の数）。"
                            + "ほかに 10 件は判定の対象外です（" + NOT_JUDGED + "）。");
            json.extractingPath("$.counts.failed").asNumber().isEqualTo(2);
            json.extractingPath("$.run.baseCommitSha").isEqualTo(PASSING);
            json.extractingPath("$.run.tags[0]").isEqualTo("v1.2.0");
            // 不合格の指標には、どこを直せばよいかを添える
            json.extractingPath("$.metrics[1].metricId").isEqualTo("M-06");
            json.extractingPath("$.metrics[1].findingCount").asNumber().isEqualTo(1);
            json.extractingPath("$.metrics[1].findings[0].location")
                    .isEqualTo("backend/src/main/java/com/qualitygate/Good.java:42");
            json.extractingPath("$.metrics[1].findings[0].url").asString()
                    .startsWith("https://github.com/ymiyamoto63/quality-gate/blob/" + FAILING + "/backend/");
        });

        FixtureWriter.write("release-report.json", response.getResponse().getContentAsString());
    }

    @Test
    void 対象外の結果は合否に数えず判定の対象外として理由と並べる() {
        String commitSha = "5".repeat(40);
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, "main", Instant.parse("2026-09-25T03:00:00Z"), 1);
        run.setComponents(List.of("backend", "frontend"));
        run.finalizeIngest();
        runs.save(run);
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO_85);
        attach(run, ArtifactType.LCOV, "lcov.info", "frontend", "SF:src/a.ts\nBRF:10\nBRH:9\nend_of_record\n");
        attach(run, ArtifactType.PIT_XML, "mutations.xml", "backend",
                "<mutations>" + "<mutation status='KILLED'><sourceFile>Good.java</sourceFile>"
                        + "<mutatedClass>com.qualitygate.Good</mutatedClass></mutation>" + "</mutations>");
        QualityGateProperties.Gate gate = new QualityGateProperties.Gate(
                GateThresholds.ALL_METRICS.stream().filter(id -> !List.of("M-01", "M-02").contains(id)).toList(),
                null, null, null, List.of("backend"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        evaluate(run, gate);

        assertThat(mvc.get().uri("/api/v1/release?ref={ref}", commitSha))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.decision").isEqualTo("RELEASABLE");
                    // M-01 の backend / frontend と M-02 の backend。frontend の M-02 は数えない
                    json.extractingPath("$.counts.judged").asNumber().isEqualTo(3);
                    json.extractingPath("$.notJudged").asArray().hasSize(11);
                    json.extractingPath("$.notJudged[0].metricId").isEqualTo("M-02");
                    json.extractingPath("$.notJudged[0].componentName").isEqualTo("frontend");
                    json.extractingPath("$.notJudged[0].reason").asString().contains("JVM");
                    json.extractingPath("$.decisionReason").asString()
                            .contains("（11 件は判定の対象外です: ミューテーションスコア（frontend）、応答時間 p95、");
                });
    }

    @Test
    void 同じコミットを計測し直したら最後の判定を使う() {
        assertThat(mvc.get().uri("/api/v1/release?ref={ref}", REMEASURED))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.decision").isEqualTo("RELEASABLE");
                    json.extractingPath("$.run.measuredAt").isEqualTo("2026-09-24T03:00:00Z");
                });
    }

    @Test
    void 指定が無ければ最新の計測で判定する() {
        assertThat(mvc.get().uri("/api/v1/release"))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.commitSha").isEqualTo(REMEASURED);
                    json.extractingPath("$.decision").isEqualTo("RELEASABLE");
                });
    }

    @Test
    void 計測していないコミットは未計測() {
        String unknown = "9".repeat(40);
        assertThat(mvc.get().uri("/api/v1/release?ref={ref}", unknown))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    // 測っていないものを合格とはみなさない
                    json.extractingPath("$.decision").isEqualTo("NOT_MEASURED");
                    json.extractingPath("$.decisionReason").asString().contains("まだ計測されていません");
                    json.extractingPath("$.run").isNull();
                    json.extractingPath("$.metrics").asArray().isEmpty();
                });
    }

    @Test
    void 一度も計測していなければ未計測() {
        IntegrationCleanup.deleteAll(jdbc);
        assertThat(mvc.get().uri("/api/v1/release"))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.decision").isEqualTo("NOT_MEASURED");
                    json.extractingPath("$.commitSha").isNull();
                });
        assertThat(mvc.get().uri("/api/v1/release/history"))
                .hasStatusOk().bodyJson().extractingPath("$.items").asArray().isEmpty();
        assertThat(mvc.get().uri("/api/v1/release/trends"))
                .hasStatusOk().bodyJson().extractingPath("$.runs").asArray().isEmpty();
    }

    @Test
    void タグは計測時に送られたタグからコミットに解決する() {
        assertThat(mvc.get().uri("/api/v1/release?ref={ref}", "release/2026-09"))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.commitSha").isEqualTo(FAILING);
                    json.extractingPath("$.decision").isEqualTo("NOT_RELEASABLE");
                });
    }

    @Test
    void 指定の誤りは理由つきで拒否する() {
        // タグを付けて計測したコミットが無い
        assertThat(mvc.get().uri("/api/v1/release?ref=v9.9.9"))
                .hasStatus(404).bodyText().contains("タグ v9.9.9 を付けたコミットの計測がありません");
        assertThat(mvc.get().uri("/api/v1/release?ref={ref}", "v".repeat(256))).hasStatus(400);
    }

    @Test
    void 履歴は新しい順で同じコミットは最後の判定だけを出す() throws Exception {
        var response = mvc.get().uri("/api/v1/release/history").exchange();

        assertThat(response).hasStatusOk().bodyJson().satisfies(content -> {
            var json = content.assertThat();
            json.extractingPath("$.items").asArray().hasSize(3);
            json.extractingPath("$.items[0].commitSha").isEqualTo(REMEASURED);
            json.extractingPath("$.items[0].verdict").isEqualTo("PASS");
            json.extractingPath("$.items[0].ref").isEqualTo(REMEASURED);
            // タグがあればタグで開く
            json.extractingPath("$.items[1].ref").isEqualTo("v1.2.0");
            json.extractingPath("$.items[1].verdict").isEqualTo("FAIL");
            json.extractingPath("$.items[2].commitSha").isEqualTo(PASSING);
        });

        FixtureWriter.write("release-history.json", response.getResponse().getContentAsString());
    }

    @Test
    void 推移は履歴と同じ計測を古い順に並べ指標ごとの値を返す() throws Exception {
        var response = mvc.get().uri("/api/v1/release/trends").exchange();

        assertThat(response).hasStatusOk().bodyJson().satisfies(content -> {
            var json = content.assertThat();
            // 同じコミットは最後の判定だけ（履歴と同じ数え方）
            json.extractingPath("$.runs").asArray().hasSize(3);
            json.extractingPath("$.runs[0].commitSha").isEqualTo(PASSING);
            json.extractingPath("$.runs[1].ref").isEqualTo("v1.2.0");
            json.extractingPath("$.runs[2].measuredAt").isEqualTo("2026-09-24T03:00:00Z");
            json.extractingPath("$.metrics[0].metricId").isEqualTo("M-01");
            json.extractingPath("$.metrics[0].axisMax").asNumber().isEqualTo(100);
            json.extractingPath("$.metrics[0].series[0].componentName").isEqualTo("backend");
            json.extractingPath("$.metrics[0].series[0].points[1].status").isEqualTo("FAIL");
            json.extractingPath("$.metrics[0].series[0].points[1].value").asNumber().isEqualTo(75.0);
            json.extractingPath("$.metrics[0].series[0].points[1].thresholdValue").asNumber().isEqualTo(80);
            json.extractingPath("$.metrics[0].series[0].points[1].threshold").isEqualTo("≥ 80%");
            json.extractingPath("$.metrics[0].series[0].points[1].thresholdRelaxed").isEqualTo(false);
            json.extractingPath("$.metrics[1].series[0].points[1].thresholdRelaxed").isEqualTo(true);
            json.extractingPath("$.metrics[1].series[0].points[1].defaultThreshold")
                    .isEqualTo("関数ごとの循環的複雑度の上限 15");
            json.extractingPath("$.metrics[1].metricId").isEqualTo("M-06");
            json.extractingPath("$.metrics[1].axisMax").isNull();
        });

        FixtureWriter.write("metric-trends.json", response.getResponse().getContentAsString());
    }

    @Test
    void ログインしていなければ取得できない() {
        MockMvcTester anonymous = MockMvcTester.create(MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity()).build());
        assertThat(anonymous.get().uri("/api/v1/release")).hasStatus(401);
        assertThat(anonymous.get().uri("/api/v1/release/history")).hasStatus(401);
        assertThat(anonymous.get().uri("/api/v1/release/trends")).hasStatus(401);
    }

    /** 分岐 20 のうち {@code covered} を通り、循環的複雑度 {@code complexity} の関数が 1 つある計測。 */
    private void evaluated(String commitSha, String measuredAt, int covered, int complexity) {
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, "main",
                Instant.parse(measuredAt), runs.findMaxAttempt(repositoryId, commitSha) + 1);
        // 前のリリースと比べた計測（PASSING の比較元は計測していないコミット）
        if (PASSING.equals(commitSha)) {
            run.setBaseCommitSha("0".repeat(40));
        } else if (FAILING.equals(commitSha)) {
            run.setBaseCommitSha(PASSING);
            // 収集ランナーが送る、計測したコミットを指すタグ
            run.setTags(List.of("v1.2.0", "release/2026-09"));
        }
        run.finalizeIngest();
        runs.save(run);
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="quality-gate">
                  <package name="com/qualitygate">
                    <class name="com/qualitygate/Good" sourcefilename="Good.java">
                      <counter type="BRANCH" missed="%d" covered="%d"/>
                    </class>
                  </package>
                  <package name="com/qualitygate/generated">
                    <class name="com/qualitygate/generated/Api" sourcefilename="Api.java">
                      <counter type="BRANCH" missed="10" covered="0"/>
                    </class>
                  </package>
                </report>
                """.formatted(20 - covered, covered));
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", """
                <?xml version="1.0" encoding="UTF-8"?>
                <pmd version="7.0.0">
                  <file name="/build/backend/src/main/java/com/qualitygate/Good.java">
                    <violation beginline="42" rule="CyclomaticComplexity" method="decide">
                The method 'decide()' has a cyclomatic complexity of %d.
                    </violation>
                  </file>
                </pmd>
                """.formatted(complexity));
        evaluate(run, GATE);
    }

    /** 取り込みの確定と同じ手順（正規化 → 判定）。 */
    private void evaluate(Run run, QualityGateProperties.Gate gate) {
        List<ArtifactRecord> records = artifacts.findByRunId(run.getId());
        GateThresholds thresholds = GateThresholds.from(gate);
        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions());
        evaluationService.evaluate(run.getId(), input, thresholds);
    }

    private void attach(Run run, ArtifactType type, String filename, String component, String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(), component, null));
    }
}
