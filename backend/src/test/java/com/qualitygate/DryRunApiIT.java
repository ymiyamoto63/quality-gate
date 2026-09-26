package com.qualitygate;

import com.qualitygate.config.GateConfigService;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.JobRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 設定変更のドライラン（FR-02-5）。過去の Run を保存前の設定で判定し直し、何も保存しない。 */
@SpringBootTest
@AbstractIntegrationTest
class DryRunApiIT {

    private static final String CONFIG = """
            version: 1
            metrics:
              branch_coverage:
                threshold: %d
              mutation_score: { enabled: false }
              performance: { enabled: false }
              vulnerabilities: { enabled: false }
              cyclomatic_complexity: { enabled: false }
              api_contract: { enabled: false }
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
    @Autowired JobRepository jobs;
    @Autowired GateConfigRepository gateConfigs;
    @Autowired ArtifactStore artifactStore;
    @Autowired ReportNormalizer normalizer;
    @Autowired RunEvaluationService evaluationService;
    @Autowired GateConfigService gateConfigService;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;

    private UUID repositoryId;
    private MockMvcTester mvc;
    private UUID failingRun;
    private UUID passingRun;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        jobs.deleteAll();
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
        users.save(new UserAccount(Uuid7.generate(), "viewer", UserRole.VIEWER, UserStatus.ACTIVE, null));
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(),
                "ymiyamoto63", "quality-gate", admin.getId())).getId();
        mvc = TestSessions.tester(context);

        failingRun = evaluated("2026-09-10T03:00:00Z", "main", 15);   // 75% → FAIL（しきい値 80）
        passingRun = evaluated("2026-09-20T03:00:00Z", "main", 17);   // 85% → PASS
        evaluated("2026-09-21T03:00:00Z", "feature/x", 10);           // 既定ブランチ以外は対象外
    }

    @Test
    void しきい値を下げると不合格のRunが合格に変わると試算する() throws Exception {
        var response = dryRun(CONFIG.formatted(70), "ymiyamoto63", "ADMIN");

        assertThat(response).hasStatusOk().bodyJson().satisfies(content -> {
            var json = content.assertThat();
            json.extractingPath("$.evaluated").asNumber().isEqualTo(2);
            json.extractingPath("$.verdictChanged").asNumber().isEqualTo(1);
            json.extractingPath("$.newlyPassing").asNumber().isEqualTo(1);
            json.extractingPath("$.newlyFailing").asNumber().isEqualTo(0);
            // 新しい順
            json.extractingPath("$.runs[1].runId").isEqualTo(failingRun.toString());
            json.extractingPath("$.runs[1].currentVerdict").isEqualTo("FAIL");
            json.extractingPath("$.runs[1].simulatedVerdict").isEqualTo("PASS");
            json.extractingPath("$.runs[1].changes[0].metricId").isEqualTo("M-01");
            json.extractingPath("$.runs[1].changes[0].currentStatus").isEqualTo("FAIL");
            json.extractingPath("$.runs[1].changes[0].simulatedStatus").isEqualTo("PASS");
            json.extractingPath("$.runs[0].changes").asArray().isEmpty();
        });
        FixtureWriter.write("dry-run.json", response.getResponse().getContentAsString());

        // 何も保存しない
        assertThat(runs.findById(failingRun).orElseThrow().getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(measurements.findByRunId(failingRun)).singleElement()
                .satisfies(m -> assertThat(m.getStatus().name()).isEqualTo("FAIL"));
        assertThat(gateConfigs.findAll()).allSatisfy(config ->
                assertThat(config.getRawYaml()).contains("threshold: 80"));
    }

    @Test
    void しきい値を上げると合格のRunが不合格に変わると試算する() {
        assertThat(dryRun(CONFIG.formatted(90), "ymiyamoto63", "ADMIN")).hasStatusOk().bodyJson()
                .satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.newlyFailing").asNumber().isEqualTo(1);
                    json.extractingPath("$.runs[0].runId").isEqualTo(passingRun.toString());
                    json.extractingPath("$.runs[0].simulatedVerdict").isEqualTo("FAIL");
                });
    }

    @Test
    void 設定の誤りは行番号つきで拒否する() {
        assertThat(dryRun("version: 1\nmetrics:\n  unknown_metric: {}\n", "ymiyamoto63", "ADMIN"))
                .hasStatus(422).bodyJson().satisfies(content -> content.assertThat()
                        .extractingPath("$.errors[0].line").asNumber().isEqualTo(3));
    }

    @Test
    void 閲覧者は試算できない() {
        assertThat(dryRun(CONFIG.formatted(70), "viewer", "VIEWER")).hasStatus(403);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult dryRun(String yaml, String login, String role) {
        return mvc.post().uri("/api/v1/repositories/{id}/config/dry-run", repositoryId)
                .with(TestSessions.as(login, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rawYaml", yaml)))
                .exchange();
    }

    private UUID evaluated(String measuredAt, String branch, int covered) {
        Instant at = Instant.parse(measuredAt);
        String commitSha = String.format("%040x", Math.abs((measuredAt + branch).hashCode()));
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, branch, "github-actions", at, 1);
        run.finalizeIngest();
        runs.save(run);
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, CONFIG.formatted(80));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="quality-gate">
                  <package name="com/qualitygate">
                    <class name="com/qualitygate/Good" sourcefilename="Good.java">
                      <counter type="BRANCH" missed="%d" covered="%d"/>
                    </class>
                  </package>
                </report>
                """.formatted(20 - covered, covered));
        List<ArtifactRecord> records = artifacts.findByRunId(run.getId());
        GateConfigService.Resolved config = gateConfigService.resolve(run, records);
        GateThresholds thresholds = GateThresholds.from(config.document());
        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions());
        evaluationService.evaluate(run.getId(), input, thresholds,
                config.isDefault() ? null : config.gateConfig().getId());
        return run.getId();
    }

    private void attach(Run run, ArtifactType type, String filename, String component, String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(), component, null, null));
    }
}
