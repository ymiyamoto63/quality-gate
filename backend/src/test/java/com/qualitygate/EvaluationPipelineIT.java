package com.qualitygate;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.IngestToken;
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
import com.qualitygate.domain.model.RunnerType;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.IngestTokenRepository;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.domain.report.NormalizedInput;
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

    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired IngestTokenRepository tokens;
    @Autowired RunRepository runs;
    @Autowired RunSkippedMetricRepository skippedMetrics;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired MeasurementRepository measurements;
    @Autowired FindingRepository findings;
    @Autowired RepositorySummaryRepository summaries;
    @Autowired JobRepository jobs;
    @Autowired ArtifactStore artifactStore;
    @Autowired ReportNormalizer normalizer;
    @Autowired RunEvaluationService evaluationService;

    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        jobs.deleteAll();
        findings.deleteAll();
        measurements.deleteAll();
        artifacts.deleteAll();
        skippedMetrics.deleteAll();
        summaries.deleteAll();
        runs.deleteAll();
        tokens.deleteAll();
        repositories.deleteAll();
        users.deleteAll();

        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(),
                "ymiyamoto63", "quality-gate", admin.getId())).getId();
        tokens.save(new IngestToken(Uuid7.generate(), repositoryId, "pfx",
                "hash", "IT", admin.getId()));
    }

    @Test
    void 脆弱性があれば不合格になり読み取りモデルに反映される() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_HIGH);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", null, PMD);

        Run evaluated = evaluate(run);

        assertThat(evaluated.getStatus()).isEqualTo(RunStatus.EVALUATED);
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(evaluated.getCompleteness()).isEqualTo(Completeness.FULL);

        assertThat(measurements.findByRunId(run.getId()))
                .extracting(Measurement::getMetricId, Measurement::getStatus)
                .containsExactlyInAnyOrder(
                        // カバレッジ 90% は合格
                        org.assertj.core.groups.Tuple.tuple("M-01", MeasurementStatus.PASS),
                        // High 1 件で不合格
                        org.assertj.core.groups.Tuple.tuple("M-06", MeasurementStatus.FAIL),
                        // ベース比較ができないため新規関数数は 0 で合格
                        org.assertj.core.groups.Tuple.tuple("M-07", MeasurementStatus.PASS));

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
    void 成果物が無い指標は申告が無ければ不合格になる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);

        Run evaluated = evaluate(run);

        // 計測できていないものを合格扱いにしない（fail-closed）
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(run.getId()))
                .filteredOn(m -> m.getStatus() == MeasurementStatus.ERROR)
                .extracting(Measurement::getMetricId)
                .containsExactlyInAnyOrder("M-06", "M-07");
    }

    @Test
    void 申告されたスキップは未計測として扱われ部分計測になる() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", null, JACOCO);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, null, TRIVY_CLEAN);
        skippedMetrics.save(new RunSkippedMetric(run.getId(), "M-07",
                "GitHub ホストランナーのため実行しない", true));

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
        // accepted=false は「申告したが設定で許容されていない」
        skippedMetrics.save(new RunSkippedMetric(run.getId(), "M-07", "理由なく省略", false));

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

    private Run evaluate(Run run) {
        NormalizedInput input = normalizer.normalize(
                artifacts.findByRunId(run.getId()), List.of("**/generated/**"));
        evaluationService.evaluate(run.getId(), input, GateThresholds.defaults());
        return runs.findById(run.getId()).orElseThrow();
    }

    private Run createRun(Instant measuredAt) {
        int attempt = runs.findMaxAttempt(repositoryId, commitOf(measuredAt)) + 1;
        Run run = new Run(Uuid7.generate(), repositoryId, commitOf(measuredAt), "main",
                RunnerType.SELF_HOSTED, "github-actions", measuredAt, attempt);
        run.finalizeIngest();
        return runs.save(run);
    }

    private static String commitOf(Instant measuredAt) {
        return String.format("%040x", Math.abs(measuredAt.hashCode()));
    }

    private void attach(Run run, ArtifactType type, String filename, String component,
                        String scope, String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(),
                component, scope, null));
    }
}
