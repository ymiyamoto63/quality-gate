package com.qualitygate;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.GateConfig;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.RunSkippedMetric;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.config.GateConfigService;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.evaluate.GateThresholds;
import com.qualitygate.evaluate.RunEvaluationService;
import com.qualitygate.normalize.ReportNormalizer;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.platform.storage.StoredArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 取り込んだ成果物が正規化され、判定され、読み取りモデルに反映されるまでを検証する。
 */
@SpringBootTest
@AbstractIntegrationTest
class EvaluationPipelineIT {

    private static final String JACOCO = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="quality-gate">
              <package name="com/qualitygate">
                <class name="com/qualitygate/Good" sourcefilename="Good.java">
                  <counter type="BRANCH" missed="2" covered="18"/>
                </class>
              </package>
            </report>
            """;

    private static final String TRIVY_HIGH = """
            { "version": "2.1.0", "runs": [{
              "tool": { "driver": { "name": "Trivy", "rules": [
                { "id": "CVE-2026-1234", "properties": { "security-severity": "8.1" } } ]}},
              "results": [{ "ruleId": "CVE-2026-1234", "level": "error",
                "message": { "text": "example-lib の任意コード実行" },
                "properties": { "package": "com.example:example-lib" },
                "locations": [{ "physicalLocation": {
                  "artifactLocation": { "uri": "backend/pom.xml" } }}] }]
            }]}
            """;

    private static final String TRIVY_CLEAN = """
            { "version": "2.1.0", "runs": [{
              "tool": { "driver": { "name": "Trivy", "rules": [] }},
              "results": []
            }]}
            """;

    /** 違反の無い axe-core の結果（ログイン画面を WCAG 2.2 AA のタグで検査）。 */
    private static final String AXE_CLEAN = """
            [{ "url": "http://localhost:5173/login",
               "testEngine": { "name": "axe-core", "version": "4.13.0" },
               "toolOptions": { "runOnly": { "type": "tag",
                 "values": ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"] } },
               "violations": [] }]
            """;

    /** 契約テストがすべて成功した Failsafe の結果。 */
    private static final String JUNIT_CLEAN = """
            <testsuite name="com.qualitygate.RunQueryApiIT" tests="2">
              <testcase classname="com.qualitygate.RunQueryApiIT" name="Run詳細を返す"/>
              <testcase classname="com.qualitygate.RunQueryApiIT" name="違反一覧を返す"/>
            </testsuite>
            """;

    /** 破壊的変更の無い oasdiff の結果。 */
    private static final String OASDIFF_CLEAN = "[]";

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
    @Autowired ArtifactStore artifactStore;
    @Autowired ReportNormalizer normalizer;
    @Autowired RunEvaluationService evaluationService;
    @Autowired GateConfigService gateConfigService;
    @Autowired GateConfigRepository gateConfigs;

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
    void 脆弱性があれば不合格になり読み取りモデルに反映される() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_HIGH);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);
        attachPit(run, "changed");
        attachAxe(run, AXE_CLEAN);
        attachContract(run);

        Run evaluated = evaluate(run);

        assertThat(evaluated.getStatus()).isEqualTo(RunStatus.EVALUATED);
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(evaluated.getCompleteness()).isEqualTo(Completeness.FULL);

        assertThat(measurements.findByRunId(run.getId()))
                .extracting(Measurement::getMetricId, Measurement::getStatus)
                .containsExactlyInAnyOrder(
                        // カバレッジ 90% は合格
                        org.assertj.core.groups.Tuple.tuple("M-01", MeasurementStatus.PASS),
                        // ミューテーションスコア 80% は合格
                        org.assertj.core.groups.Tuple.tuple("M-02", MeasurementStatus.PASS),
                        // 専有ランナーで 3 回、p95 300ms 前後・エラー 0 件なので性能は合格
                        org.assertj.core.groups.Tuple.tuple("M-03", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-04", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-05", MeasurementStatus.PASS),
                        // High 1 件で不合格
                        org.assertj.core.groups.Tuple.tuple("M-06", MeasurementStatus.FAIL),
                        // ベース比較ができないため新規関数数は 0 で合格
                        org.assertj.core.groups.Tuple.tuple("M-07", MeasurementStatus.PASS),
                        // 破壊的変更は無い
                        org.assertj.core.groups.Tuple.tuple("M-09", MeasurementStatus.PASS),
                        // 重大なアクセシビリティ違反は無い
                        org.assertj.core.groups.Tuple.tuple("M-10", MeasurementStatus.PASS),
                        // テストはすべて成功し、スキップも無い
                        org.assertj.core.groups.Tuple.tuple("M-11", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-12", MeasurementStatus.PASS));

        // 初回 Run なので違反はすべて INITIAL。NEW にすると
        // 「この変更が問題を持ち込んだ」という誤った表示になる
        assertThat(findings.findByRunId(run.getId()))
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.getState()).isEqualTo(FindingState.INITIAL));

        var summary = summaries.findById(repositoryId).orElseThrow();
        assertThat(summary.getLatestVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(summary.getOpenHighCount()).isEqualTo(1);
        // 完全計測なので最後の完全計測も進む
        assertThat(summary.getLastFullMeasuredAt()).isEqualTo(run.getMeasuredAt());
        assertThat(summary.getCategoryStatus()).contains("セキュリティ").contains("FAIL");
    }

    @Test
    void 性能は同じ計測環境の前回値とだけ比べる() {
        Run first = createRun(Instant.parse("2026-09-21T00:00:00Z"));
        attachAllMetrics(first);
        attachPit(first, "changed");
        evaluate(first);

        Run second = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attachAllMetrics(second);
        attachPit(second, "changed");
        evaluate(second);

        assertThat(measurements.findByRunId(second.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-03"))
                .singleElement()
                .satisfies(m -> assertThat(m.getPreviousValue()).isEqualByComparingTo("302"));
    }

    @Test
    void 成果物が無い指標は申告が無ければ不合格になる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);

        Run evaluated = evaluate(run);

        // 計測できていないものを合格扱いにしない（fail-closed）
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getStatus() == MeasurementStatus.ERROR)
                .extracting(Measurement::getMetricId)
                .containsExactlyInAnyOrder("M-02", "M-06", "M-07", "M-09", "M-10", "M-11", "M-12");
    }

    @Test
    void 申告されたスキップは未計測として扱われ部分計測になる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_CLEAN);
        // 既定の skippable_metrics は mutation_score / performance のみ。
        // 複雑度のスキップを受理させるには設定でそう書く必要がある。
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, """
                version: 1
                execution:
                  skippable_metrics: [cyclomatic_complexity]
                """);
        skippedMetrics.save(new RunSkippedMetric(run.getId(), "M-07",
                "PR の計測では実行しない"));
        attachPit(run, "changed");
        attachAxe(run, AXE_CLEAN);
        attachContract(run);

        Run evaluated = evaluate(run);

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(evaluated.getCompleteness()).isEqualTo(Completeness.PARTIAL);

        // 部分計測では最後の完全計測を進めない。進めると鮮度監視が機能しなくなる。
        assertThat(summaries.findById(repositoryId).orElseThrow()
                .getLastFullMeasuredAt()).isNull();
    }

    @Test
    void 許容されないスキップ申告は計測エラーになる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_CLEAN);
        // 既定の設定は複雑度のスキップを許容していない
        skippedMetrics.save(new RunSkippedMetric(run.getId(), "M-07", "理由なく省略"));

        Run evaluated = evaluate(run);

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-07"))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getStatus()).isEqualTo(MeasurementStatus.ERROR);
                    assertThat(m.getReason()).contains("スキップを許容していません");
                });
    }

    @Test
    void 形式不正の成果物は該当指標だけが計測エラーになる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "broken.sarif", null, null, "{\"not\":\"sarif\"}");
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);

        Run evaluated = evaluate(run);

        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-06"))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getStatus()).isEqualTo(MeasurementStatus.ERROR);
                    assertThat(m.getReason()).contains("解釈できませんでした");
                });
        // 他の指標は通常どおり判定される
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-01"))
                .singleElement()
                .satisfies(m -> assertThat(m.getStatus()).isEqualTo(MeasurementStatus.PASS));
    }

    @Test
    void 二回目のRunでは前回との差分から新規と解消を判定する() {
        // 1 回目: High 1 件
        Run first = createRun(Instant.parse("2026-09-21T00:00:00Z"));
        attach(first, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(first, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_HIGH);
        attach(first, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);
        evaluate(first);

        // 2 回目: 脆弱性は解消し、代わりに別の高が出る
        String other = TRIVY_HIGH.replace("CVE-2026-1234", "CVE-2026-5678")
                .replace("example-lib", "another-lib");
        Run second = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(second, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(second, ArtifactType.SARIF, "trivy.sarif", null, null, other);
        attach(second, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);
        evaluate(second);

        var states = findings.findByRunId(second.getId()).stream()
                .filter(f -> f.getMetricId().equals("M-06"))
                .toList();

        assertThat(states).extracting(f -> f.getState())
                .containsExactlyInAnyOrder(FindingState.NEW, FindingState.RESOLVED);
        // 複雑度の違反は前回から継続している
        assertThat(findings.findByRunId(second.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-07"))
                .allSatisfy(f -> assertThat(f.getState()).isEqualTo(FindingState.CONTINUING));
    }

    @Test
    void ミューテーションスコアは対象のbackendだけを判定しfrontendは対象外と示す() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, """
                version: 1
                metrics:
                  mutation_score:
                    components: [backend]
                  vulnerabilities:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                  accessibility:
                    enabled: false
                  api_contract:
                    enabled: false
                  test_results:
                    enabled: false
                  performance:
                    enabled: false
                """);
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.LCOV, "lcov.info", "frontend", null,
                "SF:src/api/format.ts\nBRF:10\nBRH:9\nend_of_record\n");
        attachPit(run, "changed");

        Run evaluated = evaluate(run);

        // 対象外は不合格にも部分計測にもしない。測りようのないものを積み残し扱いにしない
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(evaluated.getCompleteness()).isEqualTo(Completeness.FULL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-02"))
                .extracting(Measurement::getComponentName, Measurement::getStatus,
                        Measurement::getVariant)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("backend", MeasurementStatus.PASS,
                                "changed"),
                        org.assertj.core.groups.Tuple.tuple("frontend",
                                MeasurementStatus.NOT_APPLICABLE, null));
    }

    @Test
    void ミューテーションスコアの前回値は実行範囲が同じRunからだけ引く() {
        Run first = createRun(Instant.parse("2026-09-20T00:00:00Z"));
        attachAllMetrics(first);
        attachPit(first, "changed", 8);
        evaluate(first);

        Run second = createRun(Instant.parse("2026-09-21T00:00:00Z"));
        attachAllMetrics(second);
        attachPit(second, "changed", 7);
        evaluate(second);

        // 全量の値を変更範囲の値と比べた差は、品質の変化ではなく範囲の違い
        Run third = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attachAllMetrics(third);
        attachPit(third, "all", 9);
        evaluate(third);

        Measurement changed = mutationOf(second);
        assertThat(changed.getPreviousValue()).isEqualByComparingTo("80");
        // 70% は合格ラインを満たすが、前回から 10 ポイント落ちている
        assertThat(changed.getStatus()).isEqualTo(MeasurementStatus.WARN);

        Measurement all = mutationOf(third);
        assertThat(all.getVariant()).isEqualTo("all");
        assertThat(all.getPreviousValue()).isNull();
    }

    @Test
    void アクセシビリティ違反は不合格にするがダッシュボードの脆弱性件数には数えない() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_CLEAN);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);
        attachPit(run, "changed");
        // 別の成果物で同じ画面を検査した結果（ライト / ダークなど）は、同じ違反として 1 件に数える
        String violation = """
                [{ "url": "http://localhost:5173/runs/0190f5a2-7c1e-7a3b-9e4d-2f6a8b1c3d5e",
                   "violations": [{ "id": "image-alt", "impact": "critical", "tags": ["wcag2a"],
                     "help": "Images must have alternate text",
                     "nodes": [{ "target": ["img.logo"] }] }] }]
                """;
        attachAxe(run, "axe-light.json", violation);
        attachAxe(run, "axe-dark.json",
                violation.replace("9e4d-2f6a8b1c3d5e", "9e4d-000000000000"));

        Run evaluated = evaluate(run);

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-10"))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getStatus()).isEqualTo(MeasurementStatus.FAIL);
                    assertThat(m.getValue()).isEqualByComparingTo("1");
                });
        assertThat(findings.findByRunId(run.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-10"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.getSeverity()).isEqualTo(Severity.CRITICAL);
                    assertThat(f.getFilePath()).isNull();
                });
        // ダッシュボードの「重大 N 件」は脆弱性の件数。アクセシビリティ違反を混ぜない
        var summary = summaries.findById(repositoryId).orElseThrow();
        assertThat(summary.getOpenCriticalCount()).isZero();
        assertThat(summary.getCategoryStatus()).contains("使いやすさ");
    }

    @Test
    void 設定したページが検査されていなければアクセシビリティは計測エラー() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, """
                version: 1
                metrics:
                  accessibility:
                    pages: ["/login", "/runs/:id"]
                """);
        attachAllMetrics(run);
        attachPit(run, "changed");

        Run evaluated = evaluate(run);

        // AXE_CLEAN は /login しか検査していない。/runs/:id を合格にすると、
        // 検査していない画面まで「違反 0 件」と表示される
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-10"))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getStatus()).isEqualTo(MeasurementStatus.ERROR);
                    assertThat(m.getReason()).contains("/runs/:id");
                });
    }

    @Test
    void 破壊的変更は不合格になり違反として残る() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_CLEAN);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);
        attachPit(run, "changed");
        attachAxe(run, AXE_CLEAN);
        attach(run, ArtifactType.TEST_JUNIT_XML, "TEST-RunQueryApiIT.xml", "backend", null,
                JUNIT_CLEAN);
        attach(run, ArtifactType.OASDIFF_JSON, "oasdiff.json", null, null, """
                [{ "id": "api-path-removed-without-deprecation",
                   "text": "api path removed without deprecation",
                   "level": 3, "operation": "GET", "path": "/api/v1/runs" }]
                """);

        Run evaluated = evaluate(run);

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-09"))
                .singleElement()
                .satisfies(m -> assertThat(m.getStatus()).isEqualTo(MeasurementStatus.FAIL));
        // API のパスはファイルではない。GitHub へのリンクを作らない
        assertThat(findings.findByRunId(run.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-09"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.getFilePath()).isNull();
                    assertThat(f.getSeverity()).isEqualTo(Severity.HIGH);
                });
        // 破壊的変更はダッシュボードの脆弱性件数に数えない
        assertThat(summaries.findById(repositoryId).orElseThrow().getOpenHighCount()).isZero();
    }

    @Test
    void テスト結果はコンポーネントごとに判定しスキップの増加は不合格にする() {
        String config = """
                version: 1
                metrics:
                  mutation_score:
                    enabled: false
                  vulnerabilities:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                  accessibility:
                    enabled: false
                  api_contract:
                    enabled: false
                  performance:
                    enabled: false
                  test_results:
                    min_success_rate: 100
                """;
        Run first = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(first, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, config);
        attach(first, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(first, ArtifactType.TEST_JUNIT_XML, "TEST-A.xml", "backend", null, """
                <testsuite name="A">
                  <testcase classname="A" name="a"/>
                  <testcase classname="A" name="b"><skipped/></testcase>
                </testsuite>
                """);
        attach(first, ArtifactType.TEST_JUNIT_XML, "junit.xml", "frontend", null, """
                <testsuites><testsuite name="src/a.spec.ts">
                  <testcase classname="src/a.spec.ts" name="a"/>
                </testsuite></testsuites>
                """);
        assertThat(evaluate(first).getVerdict()).isEqualTo(Verdict.PASS);

        // 2 回目: backend のスキップが 1 件増え、frontend のテストが 1 件失敗した
        Run second = createRun(Instant.parse("2026-09-23T00:00:00Z"));
        attach(second, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, config);
        attach(second, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(second, ArtifactType.TEST_JUNIT_XML, "TEST-A.xml", "backend", null, """
                <testsuite name="A">
                  <testcase classname="A" name="a"/>
                  <testcase classname="A" name="b"><skipped/></testcase>
                  <testcase classname="A" name="c"><skipped/></testcase>
                </testsuite>
                """);
        attach(second, ArtifactType.TEST_JUNIT_XML, "junit.xml", "frontend", null, """
                <testsuites><testsuite name="src/a.spec.ts">
                  <testcase classname="src/a.spec.ts" name="a"><failure message="boom"/></testcase>
                </testsuite></testsuites>
                """);

        assertThat(evaluate(second).getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(second.getId()))
                .filteredOn(m -> !m.getMetricId().equals("M-01"))
                .extracting(Measurement::getMetricId, Measurement::getComponentName,
                        Measurement::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M-11", "backend", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-11", "frontend", MeasurementStatus.FAIL),
                        org.assertj.core.groups.Tuple.tuple("M-12", "backend", MeasurementStatus.FAIL),
                        org.assertj.core.groups.Tuple.tuple("M-12", "frontend", MeasurementStatus.PASS));
        // 失敗したテストは M-11、スキップしたテストは M-12 の違反として残る。増えたスキップだけが新規
        assertThat(findings.findByRunId(second.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-12"))
                .extracting(f -> f.getState().name())
                .containsExactlyInAnyOrder("CONTINUING", "NEW");
        assertThat(findings.findByRunId(second.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-11"))
                .singleElement()
                .satisfies(f -> assertThat(f.getRuleId()).isEqualTo("failed"));
    }

    @Test
    void 走査対象を宣言したSARIFはシークレットとライセンスを別の指標で判定する() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, """
                version: 1
                metrics:
                  branch_coverage:
                    enabled: false
                  mutation_score:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                  accessibility:
                    enabled: false
                  api_contract:
                    enabled: false
                  test_results:
                    enabled: false
                  performance:
                    enabled: false
                  secrets:
                    max_secrets: 0
                  licenses:
                    max_forbidden: 0
                """);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, """
                { "version": "2.1.0", "runs": [{
                  "tool": { "driver": { "name": "Trivy", "rules": [
                    { "id": "aws-access-key-id", "properties": { "security-severity": "9.5", "tags": ["secret"] } }
                  ]}},
                  "results": [
                    { "ruleId": "aws-access-key-id", "level": "error", "message": { "text": "Secret" },
                      "locations": [{ "physicalLocation": { "artifactLocation": { "uri": "config.py" } } }] }
                  ]
                }]}
                """, "{\"scanners\":[\"vuln\",\"secret\"]}");
        attach(run, ArtifactType.SARIF, "trivy-license.sarif", null, null, """
                { "version": "2.1.0", "runs": [{
                  "tool": { "driver": { "name": "Trivy", "rules": [
                    { "id": "a:LGPL-2.1-only", "properties": { "tags": ["license"] } },
                    { "id": "a:EPL-2.0", "properties": { "tags": ["license"] } }
                  ]}},
                  "results": [
                    { "ruleId": "a:LGPL-2.1-only", "message": { "text": "Classification: restricted" },
                      "locations": [{ "physicalLocation": { "artifactLocation": { "uri": "backend/pom.xml" } } }] },
                    { "ruleId": "a:EPL-2.0", "message": { "text": "Classification: reciprocal" },
                      "locations": [{ "physicalLocation": { "artifactLocation": { "uri": "backend/pom.xml" } } }] }
                  ]
                }]}
                """, "{\"scanners\":[\"license\"]}");

        Run evaluated = evaluate(run);

        // シークレットは M-06（脆弱性）ではなく M-13 で数える
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .extracting(Measurement::getMetricId, Measurement::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M-06", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-13", MeasurementStatus.FAIL),
                        // デュアルライセンスは緩いほう（EPL-2.0）を採る
                        org.assertj.core.groups.Tuple.tuple("M-14", MeasurementStatus.PASS));
        assertThat(findings.findByRunId(run.getId()))
                .extracting(f -> f.getMetricId())
                .containsExactly("M-13");
    }

    @Test
    void シークレットを有効にしても走査を宣言していなければ計測エラー() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, """
                version: 1
                metrics:
                  branch_coverage:
                    enabled: false
                  mutation_score:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                  accessibility:
                    enabled: false
                  api_contract:
                    enabled: false
                  test_results:
                    enabled: false
                  performance:
                    enabled: false
                  secrets:
                    enabled: true
                """);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_CLEAN);

        evaluate(run);

        // 走査したか分からないものを「0 件」として合格にしない
        assertThat(measurements.findByRunId(run.getId()))
                .extracting(Measurement::getMetricId, Measurement::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M-06", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-13", MeasurementStatus.ERROR));
    }

    @Test
    void 参考値の指標は値とトレンドだけを残し合否と部分計測に影響しない() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, """
                version: 1
                metrics:
                  mutation_score:
                    enabled: false
                  vulnerabilities:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                  accessibility:
                    enabled: false
                  api_contract:
                    enabled: false
                  test_results:
                    enabled: false
                  performance:
                    enabled: false
                  duplication:
                    enabled: true
                  bundle_size:
                    enabled: true
                  lighthouse:
                    enabled: true
                """);
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.JSCPD_JSON, "jscpd-report.json", "backend", null, """
                { "statistics": { "total": { "lines": 2000, "duplicatedLines": 100, "clones": 4 } } }
                """);
        attach(run, ArtifactType.BUNDLE_SIZE_JSON, "bundle-size.json", "frontend", null, """
                { "files": [ { "path": "assets/index.js", "bytes": 300000, "gzipBytes": 102400 } ] }
                """);
        // Lighthouse は送らない（計測できなかった）

        Run evaluated = evaluate(run);

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(evaluated.getCompleteness()).isEqualTo(Completeness.FULL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> !m.getMetricId().equals("M-01"))
                .extracting(Measurement::getMetricId, Measurement::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M-15", MeasurementStatus.REFERENCE),
                        org.assertj.core.groups.Tuple.tuple("M-16", MeasurementStatus.ERROR),
                        org.assertj.core.groups.Tuple.tuple("M-17", MeasurementStatus.REFERENCE));
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-16"))
                .singleElement()
                .satisfies(m -> assertThat(m.getReason()).contains("合否には影響しません"));
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-15"))
                .singleElement()
                .satisfies(m -> assertThat(m.getValue()).isEqualByComparingTo("5.00"));
    }

    @Test
    void 比較元にOpenAPI定義が無ければ破壊的変更は対象外() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_CLEAN);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);
        attachPit(run, "changed");
        attachAxe(run, AXE_CLEAN);
        attach(run, ArtifactType.TEST_JUNIT_XML, "TEST-RunQueryApiIT.xml", "backend", null,
                JUNIT_CLEAN);
        attach(run, ArtifactType.OASDIFF_JSON, "oasdiff.json", null, null, "[]",
                "{\"baseSpecMissing\":true}");

        Run evaluated = evaluate(run);

        // 対象外は合否にも部分計測にも影響しない
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(evaluated.getCompleteness()).isEqualTo(Completeness.FULL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-09"))
                .singleElement()
                .satisfies(m -> assertThat(m.getStatus())
                        .isEqualTo(MeasurementStatus.NOT_APPLICABLE));
    }

    /** ジョブハンドラと同じ手順（設定解決 → 正規化 → 判定）を踏む。 */
    @Test
    void 設定ファイルのしきい値が判定に使われる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        // カバレッジ 90% を不合格にするしきい値を設定で与える
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, """
                version: 1
                metrics:
                  branch_coverage:
                    threshold: 95
                  mutation_score:
                    enabled: false
                  vulnerabilities:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                  accessibility:
                    enabled: false
                  api_contract:
                    enabled: false
                  test_results:
                    enabled: false
                  performance:
                    enabled: false
                """);
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);

        Run evaluated = evaluate(run);

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getMetricId()).isEqualTo("M-01");
                    assertThat(m.getStatus()).isEqualTo(MeasurementStatus.FAIL);
                    assertThat(m.getReason()).contains("95% を下回っています");
                });
        // どの設定版で判定したかが Run に残る
        assertThat(evaluated.getGateConfigId()).isNotNull();
    }

    @Test
    void 同じ内容の設定は版を増やさない() {
        String yaml = "version: 1\nmetrics:\n  branch_coverage:\n    threshold: 80\n";

        Run first = createRun(Instant.parse("2026-09-21T00:00:00Z"));
        attach(first, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, yaml);
        attach(first, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        Run firstEvaluated = evaluate(first);

        Run second = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(second, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, yaml);
        attach(second, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        Run secondEvaluated = evaluate(second);

        // 毎回新しい版を作ると、変更履歴がノイズで埋まる
        assertThat(gateConfigs.count()).isEqualTo(1);
        assertThat(secondEvaluated.getGateConfigId()).isEqualTo(firstEvaluated.getGateConfigId());
    }

    @Test
    void 設定が不正なら行番号つきで拒否する() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, """
                version: 1
                metrics:
                  branch_coverage:
                    threshold: "75%"
                  mutation_scores:
                    threshold: 60
                """);

        List<ArtifactRecord> records = artifacts.findByRunId(run.getId());

        assertThatThrownBy(() -> gateConfigService.resolve(run, records))
                .isInstanceOf(ConfigValidationException.class)
                .satisfies(e -> {
                    var errors = ((ConfigValidationException) e).errors();
                    assertThat(errors).anySatisfy(error -> {
                        assertThat(error.path()).isEqualTo("metrics.branch_coverage.threshold");
                        assertThat(error.line()).isEqualTo(4);
                        assertThat(error.message()).contains("数値を指定してください");
                    });
                    // typo には候補を添える
                    assertThat(errors).anySatisfy(error -> {
                        assertThat(error.path()).isEqualTo("metrics.mutation_scores");
                        assertThat(error.message()).contains("mutation_score");
                    });
                });
    }

    @Test
    void 設定ファイルが無ければ既定値で判定する() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_CLEAN);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);
        attachPit(run, "changed");
        attachAxe(run, AXE_CLEAN);
        attachContract(run);

        Run evaluated = evaluate(run);

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(evaluated.getGateConfigId()).isNull();
        assertThat(gateConfigs.count()).isZero();
    }

    /**
     * 設定ファイルの無い Run（D-20 より前に画面で保存した設定で判定された Run）を再評価するときは、
     * 前回の判定で使った版を使う。再評価で判定の基準が変わらないように。
     */
    @Test
    void 設定ファイルの無いRunは前回の判定で使った版で判定し直す() {
        GateConfig applied = gateConfigs.save(new GateConfig(Uuid7.generate(), repositoryId, 1, "UI", null,
                "hash", "version: 1\nmetrics:\n  branch_coverage:\n    threshold: 95\n", "{}"));
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        run.applyGateConfig(applied.getId());
        runs.save(run);

        GateConfigService.Resolved config = gateConfigService.resolve(run, artifacts.findByRunId(run.getId()));

        assertThat(config.gateConfig().getId()).isEqualTo(applied.getId());
        assertThat(config.document().metric("branch_coverage").number("threshold"))
                .contains(new java.math.BigDecimal("95"));
    }

    @Test
    void 設定で無効にした指標は判定対象から外れる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, null, """
                version: 1
                metrics:
                  mutation_score:
                    enabled: false
                  vulnerabilities:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                  accessibility:
                    enabled: false
                  api_contract:
                    enabled: false
                  test_results:
                    enabled: false
                  performance:
                    enabled: false
                """);
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);

        Run evaluated = evaluate(run);

        // 無効化した指標は成果物が無くても ERROR にならない
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(measurements.findByRunId(run.getId()))
                .extracting(Measurement::getMetricId)
                .containsExactly("M-01");
    }

    private Run evaluate(Run run) {
        List<ArtifactRecord> records = artifacts.findByRunId(run.getId());
        GateConfigService.Resolved config = gateConfigService.resolve(run, records);
        GateThresholds thresholds = GateThresholds.from(config.document());
        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions());
        evaluationService.evaluate(run.getId(), input, thresholds,
                config.isDefault() ? null : config.gateConfig().getId());
        return runs.findById(run.getId()).orElseThrow();
    }

    private Run createRun(Instant measuredAt) {
        int attempt = runs.findMaxAttempt(repositoryId, commitOf(measuredAt)) + 1;
        Run run = new Run(Uuid7.generate(), repositoryId, commitOf(measuredAt), "main",
                "github-actions", measuredAt, attempt);
        run.finalizeIngest();
        Run saved = runs.save(run);
        attachPerformance(saved);
        return saved;
    }

    /** 性能の成果物（3 回分）。性能以外を検証するテストでも、未提出で ERROR にならないよう送る。 */
    private void attachPerformance(Run run) {
        for (int i = 1; i <= 3; i++) {
            attach(run, ArtifactType.K6_SUMMARY, "k6-summary-%d.json".formatted(i), null, null,
                    PerformanceFixtures.summary(i), PerformanceFixtures.METADATA);
        }
    }

    private static String commitOf(Instant measuredAt) {
        return String.format("%040x", Math.abs(measuredAt.hashCode()));
    }

    private Measurement mutationOf(Run run) {
        return measurements.findByRunId(run.getId()).stream()
                .filter(m -> m.getMetricId().equals("M-02")).findFirst().orElseThrow();
    }

    private void attachAllMetrics(Run run) {
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_CLEAN);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);
        attachAxe(run, AXE_CLEAN);
        attachContract(run);
    }

    private void attachContract(Run run) {
        attach(run, ArtifactType.TEST_JUNIT_XML, "TEST-RunQueryApiIT.xml", "backend", null,
                JUNIT_CLEAN);
        attach(run, ArtifactType.OASDIFF_JSON, "oasdiff.json", null, null, OASDIFF_CLEAN);
    }

    private void attachAxe(Run run, String json) {
        attachAxe(run, "axe-results.json", json);
    }

    private void attachAxe(Run run, String filename, String json) {
        attach(run, ArtifactType.AXE_JSON, filename, "frontend", null, json);
    }

    private void attachPit(Run run, String mutationScope) {
        attachPit(run, mutationScope, 8);
    }

    /** 10 個の mutation のうち {@code killed} 個を検出した PIT の結果。 */
    private void attachPit(Run run, String mutationScope, int killed) {
        String xml = "<mutations>"
                + mutation("KILLED").repeat(killed)
                + mutation("SURVIVED").repeat(10 - killed)
                + "</mutations>";
        attach(run, ArtifactType.PIT_XML, "mutations.xml", "backend", null, xml,
                "{\"mutationScope\":\"%s\"}".formatted(mutationScope));
    }

    private static String mutation(String status) {
        return "<mutation status='%s'><sourceFile>Good.java</sourceFile>".formatted(status)
                + "<mutatedClass>com.qualitygate.Good</mutatedClass></mutation>";
    }

    private void attach(Run run, ArtifactType type, String filename, String component,
                        String scope, String content) {
        attach(run, type, filename, component, scope, content, null);
    }

    private void attach(Run run, ArtifactType type, String filename, String component,
                        String scope, String content, String metadata) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(),
                component, scope, metadata));
    }
}
