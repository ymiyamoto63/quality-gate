package com.qualitygate;

import com.qualitygate.config.GateConfigService;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.RunSkippedMetric;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.AuditLogRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
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

/** リリース判定（UC-10）。本物の判定パイプラインで Run を積んでから読む。 */
@SpringBootTest
@AbstractIntegrationTest
class ReleaseReportApiIT {

    /** M-01 だけを判定する（完全計測になる）。 */
    private static final String CONFIG = """
            version: 1
            exclusions:
              - "**/generated/**"
            metrics:
              branch_coverage:
                threshold: 80
              mutation_score: { enabled: false }
              performance: { enabled: false }
              vulnerabilities: { enabled: false }
              cyclomatic_complexity: { enabled: false }
              api_contract: { enabled: false }
              accessibility: { enabled: false }
            """;

    /** M-02 のスキップを許す（申告すると部分計測になる）。 */
    private static final String SKIPPABLE_CONFIG = """
            version: 1
            execution:
              skippable_metrics: [mutation_score]
            metrics:
              branch_coverage:
                threshold: 80
              performance: { enabled: false }
              vulnerabilities: { enabled: false }
              cyclomatic_complexity: { enabled: false }
              api_contract: { enabled: false }
              accessibility: { enabled: false }
            """;

    private static final String PASSING = "1".repeat(40);
    private static final String FAILING = "2".repeat(40);
    private static final String PARTIAL = "3".repeat(40);
    private static final String REMEASURED = "4".repeat(40);

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired RunSkippedMetricRepository skippedMetrics;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired AuditLogRepository auditLogs;
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
        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.VIEWER, UserStatus.ACTIVE, null));
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(),
                "ymiyamoto63", "quality-gate", admin.getId())).getId();
        mvc = MockMvcTester.create(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                        .with(user("ymiyamoto63")))
                .build());

        evaluated(PASSING, "2026-09-20T03:00:00Z", CONFIG, 17, false);       // 85% → 合格
        evaluated(FAILING, "2026-09-21T03:00:00Z", CONFIG, 15, false);       // 75% → 不合格
        evaluated(PARTIAL, "2026-09-22T03:00:00Z", SKIPPABLE_CONFIG, 17, true);
        evaluated(REMEASURED, "2026-09-23T03:00:00Z", CONFIG, 17, false);
        evaluated(REMEASURED, "2026-09-24T03:00:00Z", SKIPPABLE_CONFIG, 17, true);
    }

    @Test
    void 完全計測ですべて合格ならリリース可() throws Exception {
        // 短い SHA でも、計測済みのコミットなら GitHub API を使わずに解決する
        var response = mvc.get()
                .uri("/api/v1/repositories/{id}/release-report?ref=1111111", repositoryId).exchange();

        assertThat(response).hasStatusOk().bodyJson().satisfies(content -> {
            var json = content.assertThat();
            json.extractingPath("$.refType").isEqualTo("COMMIT");
            json.extractingPath("$.commitSha").isEqualTo(PASSING);
            json.extractingPath("$.commitUrl")
                    .isEqualTo("https://github.com/ymiyamoto63/quality-gate/commit/" + PASSING);
            json.extractingPath("$.decision").isEqualTo("RELEASABLE");
            json.extractingPath("$.decisionReason").isEqualTo("合否に使う 1 件の指標がすべて合格です。");
            json.extractingPath("$.run.completeness").isEqualTo("FULL");
            json.extractingPath("$.gateConfig.version").asNumber().isEqualTo(1);
            json.extractingPath("$.gateConfig.exclusions[0]").isEqualTo("**/generated/**");
            json.extractingPath("$.counts.judged").asNumber().isEqualTo(1);
            json.extractingPath("$.metrics[0].metricId").isEqualTo("M-01");
            json.extractingPath("$.metrics[0].threshold").isEqualTo("≥ 80%");
            json.extractingPath("$.guides[0].basisLabel").isEqualTo("業界の目安");
            json.extractingPath("$.guides[0].summary").asString().contains("分かれ道");
            json.extractingPath("$.guides[0].tools").asString().contains("JaCoCo");
        });

        FixtureWriter.write("release-report.json", response.getResponse().getContentAsString());
    }

    @Test
    void 不合格があればリリース不可で不合格の指標を理由に挙げる() {
        assertThat(mvc.get().uri("/api/v1/repositories/{id}/release-report?ref={ref}", repositoryId, FAILING))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.decision").isEqualTo("NOT_RELEASABLE");
                    json.extractingPath("$.decisionReason").asString()
                            .startsWith("1 件の指標が不合格です（ブランチカバレッジ）");
                    json.extractingPath("$.metrics[0].status").isEqualTo("FAIL");
                    json.extractingPath("$.counts.failed").asNumber().isEqualTo(1);
                    json.extractingPath("$.run.baseCommitSha").isEqualTo(PASSING);
                });
    }

    @Test
    void 部分計測しか無ければ判定できない() {
        assertThat(mvc.get().uri("/api/v1/repositories/{id}/release-report?ref={ref}", repositoryId, PARTIAL))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    // 測っていない指標を合格とみなさない
                    json.extractingPath("$.decision").isEqualTo("UNDETERMINED");
                    json.extractingPath("$.decisionReason").asString().contains("ミューテーションスコア");
                    json.extractingPath("$.run.completeness").isEqualTo("PARTIAL");
                    json.extractingPath("$.counts.skipped").asNumber().isEqualTo(1);
                });
    }

    @Test
    void 同じコミットに完全計測があればそれを使う() {
        assertThat(mvc.get().uri("/api/v1/repositories/{id}/release-report?ref={ref}", repositoryId, REMEASURED))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    // 後から部分計測があっても、完全計測の結果で判定する
                    json.extractingPath("$.decision").isEqualTo("RELEASABLE");
                    json.extractingPath("$.run.completeness").isEqualTo("FULL");
                    json.extractingPath("$.run.measuredAt").isEqualTo("2026-09-23T03:00:00Z");
                    json.extractingPath("$.otherRunCount").asNumber().isEqualTo(1);
                });
    }

    @Test
    void 計測していないコミットは判定できない() {
        String unknown = "9".repeat(40);
        assertThat(mvc.get().uri("/api/v1/repositories/{id}/release-report?ref={ref}", repositoryId, unknown))
                .hasStatusOk().bodyJson().satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.decision").isEqualTo("UNDETERMINED");
                    json.extractingPath("$.decisionReason").asString().contains("まだ計測されていません");
                    json.extractingPath("$.run").isNull();
                    json.extractingPath("$.metrics").asArray().isEmpty();
                });
    }

    @Test
    void 指定の誤りは理由つきで拒否する() {
        // テストでは GitHub API を無効にしているため、タグは解決できない
        assertThat(mvc.get().uri("/api/v1/repositories/{id}/release-report?ref=v1.2.0", repositoryId).exchange())
                .hasStatus(502);
        assertThat(mvc.get().uri("/api/v1/repositories/{id}/release-report?ref=a b", repositoryId).exchange())
                .hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/repositories/{id}/release-report?ref=1111111", UUID.randomUUID())
                .exchange()).hasStatus(404);
    }

    @Test
    void CSVを出力し監査ログに残す() throws Exception {
        var response = mvc.get()
                .uri("/api/v1/repositories/{id}/release-report.csv?ref={ref}", repositoryId, FAILING).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response.getResponse().getHeader("Content-Disposition"))
                .contains("release_ymiyamoto63_quality-gate_2222222.csv");
        List<String> lines = response.getResponse().getContentAsString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines.getFirst()).startsWith("﻿リポジトリ,指定,コミット,リリース判定");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1))
                .contains("ymiyamoto63/quality-gate", "リリース不可", "完全計測", PASSING, "v1", "M-01", "ブランチカバレッジ",
                        "75%", "≥ 80%", "不合格", "業界の目安", "2026-09-21T12:00:00+09:00", "ymiyamoto63");

        assertThat(auditLogs.findAll()).singleElement().satisfies(log -> {
            assertThat(log.getAction()).isEqualTo("RELEASE_REPORT_EXPORTED");
            assertThat(log.getTargetId()).isEqualTo(repositoryId.toString());
        });
    }

    @Test
    void 未計測でもCSVに判定できなかったことを残す() {
        var response = mvc.get()
                .uri("/api/v1/repositories/{id}/release-report.csv?ref={ref}", repositoryId, "9".repeat(40))
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyText().contains("判定できない", "まだ計測されていません");
    }

    @Test
    void ログインしていなければ取得できない() {
        MockMvcTester anonymous = MockMvcTester.create(MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity()).build());
        assertThat(anonymous.get().uri("/api/v1/repositories/{id}/release-report?ref=1111111", repositoryId)
                .exchange()).hasStatus(401);
    }

    private void evaluated(String commitSha, String measuredAt, String config, int covered, boolean skipMutation) {
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, "main", "github-actions",
                Instant.parse(measuredAt), runs.findMaxAttempt(repositoryId, commitSha) + 1);
        // 前のリリースと比べた計測（PASSING の比較元は計測していないコミット）
        if (PASSING.equals(commitSha)) {
            run.setBaseCommitSha("0".repeat(40));
        } else if (FAILING.equals(commitSha)) {
            run.setBaseCommitSha(PASSING);
        }
        run.finalizeIngest();
        runs.save(run);
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", null, config);
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
        if (skipMutation) {
            skippedMetrics.save(new RunSkippedMetric(run.getId(), "M-02", "PR の計測"));
        }
        List<ArtifactRecord> records = artifacts.findByRunId(run.getId());
        GateConfigService.Resolved resolved = gateConfigService.resolve(run, records);
        GateThresholds thresholds = GateThresholds.from(resolved.document());
        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions());
        evaluationService.evaluate(run.getId(), input, thresholds,
                resolved.isDefault() ? null : resolved.gateConfig().getId());
    }

    private void attach(Run run, ArtifactType type, String filename, String component, String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(), component, null, null));
    }
}
