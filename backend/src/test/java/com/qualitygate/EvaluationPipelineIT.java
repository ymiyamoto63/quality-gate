package com.qualitygate;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.report.NormalizedInput;
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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取り込んだ成果物が正規化され、環境変数の合格ラインで判定されるまでを検証する。
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

    /** 依存関係のライセンスがすべて許容されるライセンスの走査結果。 */
    private static final String LICENSE_CLEAN = """
            { "version": "2.1.0", "runs": [{
              "tool": { "driver": { "name": "Trivy", "rules": [
                { "id": "a:Apache-2.0", "properties": { "tags": ["license"] } } ]}},
              "results": [
                { "ruleId": "a:Apache-2.0", "message": { "text": "Classification: notice" },
                  "locations": [{ "physicalLocation": { "artifactLocation": { "uri": "backend/pom.xml" } } }] } ]
            }]}
            """;

    private static final String SCANNED_VULN_AND_SECRET = "{\"scanners\":[\"vuln\",\"secret\"]}";
    private static final String SCANNED_LICENSE = "{\"scanners\":[\"license\"]}";

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

    /** 複雑度がしきい値（15）に届かない関数だけの PMD の結果。 */
    private static final String PMD_SIMPLE = PMD.replace("big", "small").replace("of 20", "of 8");

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired MeasurementRepository measurements;
    @Autowired FindingRepository findings;
    @Autowired ArtifactStore artifactStore;
    @Autowired ReportNormalizer normalizer;
    @Autowired RunEvaluationService evaluationService;

    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(),
                "ymiyamoto63", "quality-gate", "main")).getId();
    }

    @Test
    void 脆弱性があれば不合格になる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, TRIVY_HIGH, SCANNED_VULN_AND_SECRET);
        attach(run, ArtifactType.SARIF, "trivy-license.sarif", null, LICENSE_CLEAN, SCANNED_LICENSE);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD);
        attachPit(run, "changed");
        attachAxe(run, AXE_CLEAN);
        attachContract(run);

        Run evaluated = evaluate(run);

        assertThat(evaluated.getStatus()).isEqualTo(RunStatus.EVALUATED);
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);

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
                        // High 1 件で不合格
                        org.assertj.core.groups.Tuple.tuple("M-05", MeasurementStatus.FAIL),
                        // 複雑度 20 の関数が 1 件あり不合格
                        org.assertj.core.groups.Tuple.tuple("M-06", MeasurementStatus.FAIL),
                        // 破壊的変更は無い
                        org.assertj.core.groups.Tuple.tuple("M-07", MeasurementStatus.PASS),
                        // 重大なアクセシビリティ違反は無い
                        org.assertj.core.groups.Tuple.tuple("M-08", MeasurementStatus.PASS),
                        // テストはすべて成功し、スキップも無い
                        org.assertj.core.groups.Tuple.tuple("M-09", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-10", MeasurementStatus.PASS),
                        // シークレットも使用禁止のライセンスも無い
                        org.assertj.core.groups.Tuple.tuple("M-11", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-12", MeasurementStatus.PASS));

        // 初回 Run なので違反はすべて INITIAL。NEW にすると
        // 「この変更が問題を持ち込んだ」という誤った表示になる
        assertThat(findings.findByRunId(run.getId()))
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.getState()).isEqualTo(FindingState.INITIAL));
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
    void 成果物が無い指標は計測エラーになり不合格になる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);

        Run evaluated = evaluate(run);

        // 計測できていないものを合格扱いにしない（fail-closed）
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getStatus() == MeasurementStatus.ERROR)
                .extracting(Measurement::getMetricId)
                .containsExactlyInAnyOrder("M-02", "M-05", "M-06", "M-07", "M-08", "M-09", "M-10", "M-11", "M-12");
    }

    @Test
    void 形式不正の成果物は該当指標だけが計測エラーになる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attach(run, ArtifactType.SARIF, "broken.sarif", null, "{\"not\":\"sarif\"}");
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD);

        evaluate(run);

        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-05"))
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
        attach(first, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attach(first, ArtifactType.SARIF, "trivy.sarif", null, TRIVY_HIGH);
        attach(first, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD);
        evaluate(first);

        // 2 回目: 脆弱性は解消し、代わりに別の高が出る
        String other = TRIVY_HIGH.replace("CVE-2026-1234", "CVE-2026-5678")
                .replace("example-lib", "another-lib");
        Run second = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(second, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attach(second, ArtifactType.SARIF, "trivy.sarif", null, other);
        attach(second, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD);
        evaluate(second);

        var states = findings.findByRunId(second.getId()).stream()
                .filter(f -> f.getMetricId().equals("M-05"))
                .toList();

        assertThat(states).extracting(f -> f.getState())
                .containsExactlyInAnyOrder(FindingState.NEW, FindingState.RESOLVED);
        // 複雑度の違反は前回から継続している
        assertThat(findings.findByRunId(second.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-06"))
                .allSatisfy(f -> assertThat(f.getState()).isEqualTo(FindingState.CONTINUING));
    }

    @Test
    void ミューテーションスコアは対象のbackendだけを判定しfrontendは対象外と示す() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attach(run, ArtifactType.LCOV, "lcov.info", "frontend",
                "SF:src/api/format.ts\nBRF:10\nBRH:9\nend_of_record\n");
        attachPit(run, "changed");

        Run evaluated = evaluate(run, gate(only("M-01", "M-02"), null, List.of("backend"), null));

        // 対象外は不合格にしない。測りようのないものを積み残し扱いにしない
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
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
        // 70% は合格ラインを満たす。前回から 10 ポイント落ちていることは理由に書き添える
        assertThat(changed.getStatus()).isEqualTo(MeasurementStatus.PASS);
        assertThat(changed.getReason()).contains("前回より 10.00 ポイント低下");

        Measurement all = mutationOf(third);
        assertThat(all.getVariant()).isEqualTo("all");
        assertThat(all.getPreviousValue()).isNull();
    }

    @Test
    void アクセシビリティ違反は不合格にする() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, TRIVY_CLEAN);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD);
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
                .filteredOn(m -> m.getMetricId().equals("M-08"))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getStatus()).isEqualTo(MeasurementStatus.FAIL);
                    assertThat(m.getValue()).isEqualByComparingTo("1");
                });
        assertThat(findings.findByRunId(run.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-08"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.getSeverity()).isEqualTo(Severity.CRITICAL);
                    assertThat(f.getFilePath()).isNull();
                });
    }

    @Test
    void 設定したページが検査されていなければアクセシビリティは計測エラー() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attachAllMetrics(run);
        attachPit(run, "changed");

        Run evaluated = evaluate(run, gate(List.of(), null, null, List.of("/login", "/runs/:id")));

        // AXE_CLEAN は /login しか検査していない。/runs/:id を合格にすると、
        // 検査していない画面まで「違反 0 件」と表示される
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-08"))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getStatus()).isEqualTo(MeasurementStatus.ERROR);
                    assertThat(m.getReason()).contains("/runs/:id");
                });
    }

    @Test
    void 破壊的変更は不合格になり違反として残る() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, TRIVY_CLEAN);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD);
        attachPit(run, "changed");
        attachAxe(run, AXE_CLEAN);
        attach(run, ArtifactType.TEST_JUNIT_XML, "TEST-RunQueryApiIT.xml", "backend",
                JUNIT_CLEAN);
        attach(run, ArtifactType.OASDIFF_JSON, "oasdiff.json", null, """
                [{ "id": "api-path-removed-without-deprecation",
                   "text": "api path removed without deprecation",
                   "level": 3, "operation": "GET", "path": "/api/v1/runs" }]
                """);

        Run evaluated = evaluate(run);

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-07"))
                .singleElement()
                .satisfies(m -> assertThat(m.getStatus()).isEqualTo(MeasurementStatus.FAIL));
        // API のパスはファイルではない。GitHub へのリンクを作らない
        assertThat(findings.findByRunId(run.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-07"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.getFilePath()).isNull();
                    assertThat(f.getSeverity()).isEqualTo(Severity.HIGH);
                });
    }

    @Test
    void テスト結果はコンポーネントごとに判定しスキップの増加は不合格にする() {
        QualityGateProperties.Gate gate = gate(only("M-01", "M-09", "M-10"), null, null, null);
        Run first = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(first, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attach(first, ArtifactType.TEST_JUNIT_XML, "TEST-A.xml", "backend", """
                <testsuite name="A">
                  <testcase classname="A" name="a"/>
                  <testcase classname="A" name="b"><skipped/></testcase>
                </testsuite>
                """);
        attach(first, ArtifactType.TEST_JUNIT_XML, "junit.xml", "frontend", """
                <testsuites><testsuite name="src/a.spec.ts">
                  <testcase classname="src/a.spec.ts" name="a"/>
                </testsuite></testsuites>
                """);
        assertThat(evaluate(first, gate).getVerdict()).isEqualTo(Verdict.PASS);

        // 2 回目: backend のスキップが 1 件増え、frontend のテストが 1 件失敗した
        Run second = createRun(Instant.parse("2026-09-23T00:00:00Z"));
        attach(second, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attach(second, ArtifactType.TEST_JUNIT_XML, "TEST-A.xml", "backend", """
                <testsuite name="A">
                  <testcase classname="A" name="a"/>
                  <testcase classname="A" name="b"><skipped/></testcase>
                  <testcase classname="A" name="c"><skipped/></testcase>
                </testsuite>
                """);
        attach(second, ArtifactType.TEST_JUNIT_XML, "junit.xml", "frontend", """
                <testsuites><testsuite name="src/a.spec.ts">
                  <testcase classname="src/a.spec.ts" name="a"><failure message="boom"/></testcase>
                </testsuite></testsuites>
                """);

        assertThat(evaluate(second, gate).getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(second.getId()))
                .filteredOn(m -> !m.getMetricId().equals("M-01"))
                .extracting(Measurement::getMetricId, Measurement::getComponentName,
                        Measurement::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M-09", "backend", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-09", "frontend", MeasurementStatus.FAIL),
                        org.assertj.core.groups.Tuple.tuple("M-10", "backend", MeasurementStatus.FAIL),
                        org.assertj.core.groups.Tuple.tuple("M-10", "frontend", MeasurementStatus.PASS));
        // 失敗したテストは M-09、スキップしたテストは M-10 の違反として残る。増えたスキップだけが新規
        assertThat(findings.findByRunId(second.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-10"))
                .extracting(f -> f.getState().name())
                .containsExactlyInAnyOrder("CONTINUING", "NEW");
        assertThat(findings.findByRunId(second.getId()))
                .filteredOn(f -> f.getMetricId().equals("M-09"))
                .singleElement()
                .satisfies(f -> assertThat(f.getRuleId()).isEqualTo("failed"));
    }

    @Test
    void 走査対象を宣言したSARIFはシークレットとライセンスを別の指標で判定する() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, """
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
        attach(run, ArtifactType.SARIF, "trivy-license.sarif", null, """
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

        Run evaluated = evaluate(run, gate(only("M-05", "M-11", "M-12"), null, null, null));

        // シークレットは M-05（脆弱性）ではなく M-11 で数える
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .extracting(Measurement::getMetricId, Measurement::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M-05", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-11", MeasurementStatus.FAIL),
                        // デュアルライセンスは緩いほう（EPL-2.0）を採る
                        org.assertj.core.groups.Tuple.tuple("M-12", MeasurementStatus.PASS));
        assertThat(findings.findByRunId(run.getId()))
                .extracting(f -> f.getMetricId())
                .containsExactly("M-11");
    }

    @Test
    void シークレットを有効にしても走査を宣言していなければ計測エラー() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, TRIVY_CLEAN);

        evaluate(run, gate(only("M-05", "M-11"), null, null, null));

        // 走査したか分からないものを「0 件」として合格にしない
        assertThat(measurements.findByRunId(run.getId()))
                .extracting(Measurement::getMetricId, Measurement::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M-05", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("M-11", MeasurementStatus.ERROR));
    }

    @Test
    void 比較元にOpenAPI定義が無ければ破壊的変更は対象外() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attachTrivyClean(run);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD_SIMPLE);
        attachPit(run, "changed");
        attachAxe(run, AXE_CLEAN);
        attach(run, ArtifactType.TEST_JUNIT_XML, "TEST-RunQueryApiIT.xml", "backend",
                JUNIT_CLEAN);
        attach(run, ArtifactType.OASDIFF_JSON, "oasdiff.json", null, "[]",
                "{\"baseSpecMissing\":true}");

        Run evaluated = evaluate(run);

        // 対象外は合否に影響しない
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getMetricId().equals("M-07"))
                .singleElement()
                .satisfies(m -> assertThat(m.getStatus())
                        .isEqualTo(MeasurementStatus.NOT_APPLICABLE));
    }

    @Test
    void 環境変数のしきい値が判定に使われる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);

        // カバレッジ 90% を不合格にするしきい値（QG_BRANCH_COVERAGE_MIN=95）
        Run evaluated = evaluate(run, gate(only("M-01"), new BigDecimal("95"), null, null));

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getMetricId()).isEqualTo("M-01");
                    assertThat(m.getStatus()).isEqualTo(MeasurementStatus.FAIL);
                    assertThat(m.getReason()).contains("95% を下回っています");
                });
    }

    @Test
    void 環境変数が無ければ既定の合格ラインで判定する() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attachTrivyClean(run);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD_SIMPLE);
        attachPit(run, "changed");
        attachAxe(run, AXE_CLEAN);
        attachContract(run);

        Run evaluated = evaluate(run);

        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void 無効にした指標は判定対象から外れる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);

        // QG_DISABLED_METRICS に M-01 以外を並べた場合
        Run evaluated = evaluate(run, gate(only("M-01"), null, null, null));

        // 無効化した指標は成果物が無くても ERROR にならない
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(measurements.findByRunId(run.getId()))
                .extracting(Measurement::getMetricId)
                .containsExactly("M-01");
    }

    /** 取り込みの確定と同じ手順（正規化 → 判定）を、既定の合格ラインで踏む。 */
    private Run evaluate(Run run) {
        return evaluate(run, QualityGateProperties.Gate.defaults());
    }

    private Run evaluate(Run run, QualityGateProperties.Gate gate) {
        List<ArtifactRecord> records = artifacts.findByRunId(run.getId());
        GateThresholds thresholds = GateThresholds.from(gate);
        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions());
        evaluationService.evaluate(run.getId(), input, thresholds);
        return runs.findById(run.getId()).orElseThrow();
    }

    /** 環境変数の合格ライン。null は既定値。 */
    private static QualityGateProperties.Gate gate(List<String> disabledMetrics, BigDecimal branchCoverageMin,
                                                   List<String> mutationComponents, List<String> accessibilityPages) {
        return new QualityGateProperties.Gate(disabledMetrics, null, branchCoverageMin, null, mutationComponents,
                null, null, null, null, null, null, null, null, null, null, accessibilityPages, null, null, null,
                null, null);
    }

    /** 指定した指標だけを判定する（それ以外を QG_DISABLED_METRICS に並べる）。 */
    private static List<String> only(String... metricIds) {
        return GateThresholds.ALL_METRICS.stream().filter(id -> !List.of(metricIds).contains(id)).toList();
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
            attach(run, ArtifactType.K6_SUMMARY, "k6-summary-%d.json".formatted(i), null,
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
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        attachTrivyClean(run);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", PMD);
        attachAxe(run, AXE_CLEAN);
        attachContract(run);
    }

    /** 脆弱性・シークレット・ライセンスの走査で、何も見つからなかった結果。 */
    private void attachTrivyClean(Run run) {
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, TRIVY_CLEAN, SCANNED_VULN_AND_SECRET);
        attach(run, ArtifactType.SARIF, "trivy-license.sarif", null, LICENSE_CLEAN, SCANNED_LICENSE);
    }

    private void attachContract(Run run) {
        attach(run, ArtifactType.TEST_JUNIT_XML, "TEST-RunQueryApiIT.xml", "backend",
                JUNIT_CLEAN);
        attach(run, ArtifactType.OASDIFF_JSON, "oasdiff.json", null, OASDIFF_CLEAN);
    }

    private void attachAxe(Run run, String json) {
        attachAxe(run, "axe-results.json", json);
    }

    private void attachAxe(Run run, String filename, String json) {
        attach(run, ArtifactType.AXE_JSON, filename, "frontend", json);
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
        attach(run, ArtifactType.PIT_XML, "mutations.xml", "backend", xml,
                "{\"mutationScope\":\"%s\"}".formatted(mutationScope));
    }

    private static String mutation(String status) {
        return "<mutation status='%s'><sourceFile>Good.java</sourceFile>".formatted(status)
                + "<mutatedClass>com.qualitygate.Good</mutatedClass></mutation>";
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
                component, metadata));
    }
}
