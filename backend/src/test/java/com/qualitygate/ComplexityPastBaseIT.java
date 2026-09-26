package com.qualitygate;

import com.qualitygate.config.GateConfigService;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Finding;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.FindingState;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M-07 の比較元に、比較元コミットで判定済みの過去の Run を使う（指標仕様書 M-07「ベース側の CC 取得」の 2）。
 * base スコープの解析結果を送らない CI でも、新規・悪化した関数だけを数えられる。
 * ファイルの移動・リネーム（指標仕様書 0.4）も、Run に保持した対応表で追跡する。
 */
@SpringBootTest
@AbstractIntegrationTest
class ComplexityPastBaseIT {

    private static final String BASE_COMMIT = "b".repeat(40);
    private static final String HEAD_COMMIT = "c".repeat(40);
    private static final String NEWER_COMMIT = "d".repeat(40);

    private static final String CONFIG = """
            version: 1
            metrics:
              branch_coverage: { enabled: false }
              mutation_score: { enabled: false }
              performance: { enabled: false }
              vulnerabilities: { enabled: false }
              cyclomatic_complexity:
                enabled: true
                max_complexity: 15
                warn_from: 11
              api_contract: { enabled: false }
              test_results: { enabled: false }
              accessibility: { enabled: false }
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
    void 比較元コミットの過去のRunと比べて新規と悪化だけを数える() {
        // 比較元: legacy は既に 20、worsened は 12（注意水準）、quiet は 5（保存されない）
        evaluate(BASE_COMMIT, null, Map.of("legacy", 20, "worsened", 12, "quiet", 5));

        // head: legacy は変わらず 20、worsened は 18 に悪化、quiet は 17 に悪化、added は新規で 16
        Measurement m07 = evaluate(HEAD_COMMIT, BASE_COMMIT,
                Map.of("legacy", 20, "worsened", 18, "quiet", 17, "added", 16));

        assertThat(m07.getStatus()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(m07.getValue()).isEqualByComparingTo("3");
        assertThat(m07.getDetail()).contains("\"baseSource\": \"past-run\"")
                .contains("\"baseComparisonAvailable\": true");
    }

    @Test
    void 比較元のRunに違反が無くても比較元として使う() {
        evaluate(BASE_COMMIT, null, Map.of("small", 3));

        Measurement m07 = evaluate(HEAD_COMMIT, BASE_COMMIT, Map.of("small", 16));

        assertThat(m07.getValue()).isEqualByComparingTo("1");
        assertThat(m07.getDetail()).contains("\"baseSource\": \"past-run\"");
    }

    @Test
    void 比較元コミットのRunが無ければ比較しない() {
        Measurement m07 = evaluate(HEAD_COMMIT, BASE_COMMIT, Map.of("legacy", 20));

        assertThat(m07.getStatus()).isEqualTo(MeasurementStatus.PASS);
        assertThat(m07.getValue()).isEqualByComparingTo("0");
        assertThat(m07.getDetail()).contains("\"baseComparisonAvailable\": false");
    }

    @Test
    void ファイルを移動しただけの関数は新規にも解消にもしない() {
        evaluate(BASE_COMMIT, null, Map.of("legacy", 20));

        Measurement m07 = evaluate(HEAD_COMMIT, BASE_COMMIT, "Renamed.java", Map.of("legacy", 20, "added", 16),
                "{\"backend/src/main/java/com/qualitygate/Renamed.java\": "
                        + "\"backend/src/main/java/com/qualitygate/Service.java\"}");

        // legacy は比較元（過去の Run）の Service.java の関数と同じとみなし、新規は added だけ
        assertThat(m07.getValue()).isEqualByComparingTo("1");
        assertThat(findings.findByRunId(m07.getRunId()))
                .extracting(f -> f.getTitle().contains("legacy") ? "legacy" : "added", Finding::getState)
                .containsExactlyInAnyOrder(tuple("legacy", FindingState.CONTINUING), tuple("added", FindingState.NEW));
    }

    @Test
    void 移動の対応表が無ければ移動した関数は新規になる() {
        evaluate(BASE_COMMIT, null, Map.of("legacy", 20));

        Measurement m07 = evaluate(HEAD_COMMIT, BASE_COMMIT, "Renamed.java", Map.of("legacy", 20), null);

        assertThat(m07.getValue()).isEqualByComparingTo("1");
        assertThat(findings.findByRunId(m07.getRunId())).extracting(Finding::getState)
                .containsExactlyInAnyOrder(FindingState.NEW, FindingState.RESOLVED);
    }

    @Test
    void 比較元コミットのRunを直前に計測したRunより優先して比較対象にする() {
        // リリースのタグ（HEAD）を、それより新しいコミット（NEWER）の後に計測した場合。
        // 比較対象は前のリリース（BASE）で、同じブランチで直前に計測した NEWER ではない
        Measurement base = evaluate(BASE_COMMIT, null, Instant.parse("2026-09-19T00:00:00Z"), "Service.java",
                Map.of("legacy", 20), null);
        evaluate(NEWER_COMMIT, null, Instant.parse("2026-09-20T00:00:00Z"), "Service.java", Map.of(), null);

        Measurement head = evaluate(HEAD_COMMIT, BASE_COMMIT, Map.of("legacy", 20));

        assertThat(runs.findById(head.getRunId()).orElseThrow().getBaselineRunId()).isEqualTo(base.getRunId());
        assertThat(findings.findByRunId(head.getRunId())).extracting(Finding::getState)
                .containsExactly(FindingState.CONTINUING);
    }

    private Measurement evaluate(String commit, String baseCommit, Map<String, Integer> complexity) {
        return evaluate(commit, baseCommit, "Service.java", complexity, null);
    }

    private Measurement evaluate(String commit, String baseCommit, String file, Map<String, Integer> complexity,
                                 String renamedFiles) {
        return evaluate(commit, baseCommit,
                Instant.parse(baseCommit == null ? "2026-09-20T00:00:00Z" : "2026-09-21T00:00:00Z"), file,
                complexity, renamedFiles);
    }

    private Measurement evaluate(String commit, String baseCommit, Instant measuredAt, String file,
                                 Map<String, Integer> complexity, String renamedFiles) {
        Run run = new Run(Uuid7.generate(), repositoryId, commit, "main", "it", measuredAt, 1);
        run.setBaseCommitSha(baseCommit);
        run.setRenamedFiles(renamedFiles);
        run.finalizeIngest();
        runs.save(run);
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, CONFIG);
        StringBuilder pmd = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <pmd version="7.0.0">
                  <file name="/build/backend/src/main/java/com/qualitygate/%s">
                """.formatted(file));
        complexity.forEach((method, cc) -> pmd.append("""
                    <violation beginline="10" rule="CyclomaticComplexity" method="%1$s">
                The method '%1$s()' has a cyclomatic complexity of %2$d.
                    </violation>
                """.formatted(method, cc)));
        pmd.append("  </file>\n</pmd>\n");
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", pmd.toString());

        List<ArtifactRecord> records = artifacts.findByRunId(run.getId());
        GateConfigService.Resolved config = gateConfigService.resolve(run, records);
        GateThresholds thresholds = GateThresholds.from(config.document());
        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions(), normalizer.renamesOf(run));
        evaluationService.evaluate(run.getId(), input, thresholds,
                config.isDefault() ? null : config.gateConfig().getId());
        return measurements.findByRunId(run.getId()).stream()
                .filter(m -> "M-07".equals(m.getMetricId())).findFirst().orElseThrow();
    }

    private void attach(Run run, ArtifactType type, String filename, String component, String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(), component, null, null));
    }
}
