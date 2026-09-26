package com.qualitygate;

import com.qualitygate.config.GateConfigService;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.model.ArtifactType;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** 品質レポート（FR-08-4）。本物の判定パイプラインで Run を積んでから読む。 */
@SpringBootTest
@AbstractIntegrationTest
class ReportApiIT {

    private static final String CONFIG = """
            version: 1
            metrics:
              branch_coverage:
                threshold: 80
              mutation_score: { enabled: false }
              performance: { enabled: false }
              vulnerabilities: { enabled: false }
              cyclomatic_complexity: { enabled: false }
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
    @Autowired WebApplicationContext context;

    private UUID repositoryId;
    private MockMvcTester mvc;

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
        mvc = MockMvcTester.create(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                        .with(user("viewer")))
                .build());

        evaluated("2026-09-10T03:00:00Z", "main", 15);        // 75% → FAIL
        evaluated("2026-09-20T03:00:00Z", "main", 17);        // 85% → PASS
        evaluated("2026-09-25T03:00:00Z", "feature/x", 10);   // 既定ブランチ以外は数えない
        evaluated("2026-10-05T03:00:00Z", "main", 19);        // 期間外
    }

    @Test
    void 既定ブランチの判定を期間でまとめる() throws Exception {
        var response = mvc.get()
                .uri("/api/v1/reports?from=2026-09-01&to=2026-09-30&repositoryId={id}", repositoryId)
                .exchange();

        assertThat(response).hasStatusOk().bodyJson().satisfies(content -> {
            var json = content.assertThat();
            json.extractingPath("$.zone").isEqualTo("Asia/Tokyo");
            json.extractingPath("$.repositories[0].fullName").isEqualTo("ymiyamoto63/quality-gate");
            json.extractingPath("$.repositories[0].runs").asNumber().isEqualTo(2);
            json.extractingPath("$.repositories[0].passed").asNumber().isEqualTo(1);
            json.extractingPath("$.repositories[0].failed").asNumber().isEqualTo(1);
            json.extractingPath("$.repositories[0].passRate").asNumber().isEqualTo(50.0);
            json.extractingPath("$.repositories[0].latest.verdict").isEqualTo("PASS");
            json.extractingPath("$.repositories[0].metrics[0].metricId").isEqualTo("M-01");
            json.extractingPath("$.repositories[0].metrics[0].value").asNumber().isEqualTo(85.0);
            json.extractingPath("$.repositories[0].metrics[0].firstValue").asNumber().isEqualTo(75.0);
            json.extractingPath("$.repositories[0].metrics[0].change").asNumber().isEqualTo(10.0);
        });

        FixtureWriter.write("report.json", response.getResponse().getContentAsString());
    }

    @Test
    void 明細をCSVで返す() throws Exception {
        var response = mvc.get()
                .uri("/api/v1/reports/measurements.csv?from=2026-09-01&to=2026-09-30")
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(response.getResponse().getHeader("Content-Disposition"))
                .contains("quality-report_2026-09-01_2026-09-30.csv");
        String csv = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> lines = csv.lines().toList();
        assertThat(lines.getFirst()).startsWith("﻿repository,branch,run_id,measured_at");
        // 既定ブランチの 2 Run × 判定した指標（M-01 のほか、無効にした指標は行を作らない）
        assertThat(lines.stream().filter(line -> line.contains(",M-01,")).count()).isEqualTo(2);
        assertThat(csv).contains("2026-09-10T12:00:00+09:00").doesNotContain("feature/x")
                .doesNotContain("2026-10-05");
    }

    @Test
    void 期間の誤りは理由つきで拒否する() {
        assertThat(mvc.get().uri("/api/v1/reports?from=2026-09-30&to=2026-09-01").exchange())
                .hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/reports?from=2025-01-01&to=2026-09-01").exchange())
                .hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/reports?repositoryId={id}", UUID.randomUUID()).exchange())
                .hasStatus(404);
    }

    @Test
    void ログインしていなければ取得できない() {
        MockMvcTester anonymous = MockMvcTester.create(MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity()).build());
        assertThat(anonymous.get().uri("/api/v1/reports").exchange()).hasStatus(401);
    }

    private void evaluated(String measuredAt, String branch, int covered) {
        Instant at = Instant.parse(measuredAt);
        String commitSha = String.format("%040x", Math.abs((measuredAt + branch).hashCode()));
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, branch, "github-actions", at, runs.findMaxAttempt(repositoryId, commitSha) + 1);
        run.finalizeIngest();
        runs.save(run);
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, CONFIG);
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
    }

    private void attach(Run run, ArtifactType type, String filename, String component, String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(), component, null, null));
    }
}
