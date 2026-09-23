package com.qualitygate;

import com.jayway.jsonpath.JsonPath;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.RunnerType;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.model.WaiverStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.AuditLogRepository;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.domain.repo.WaiverRepository;
import com.qualitygate.job.JobEnqueuer;
import com.qualitygate.job.JobWorker;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.platform.storage.StoredArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.qualitygate.TestSessions.as;
import static org.assertj.core.api.Assertions.assertThat;

/** 免除（S-07）・再評価・日次バッチ。免除は判定に効き、記録は必ず残る。 */
@SpringBootTest
@AbstractIntegrationTest
class WaiverApiIT {

    /** M-06 だけを判定する設定。他の指標の成果物を用意せずに免除の効果を見る。 */
    private static final String ONLY_VULNERABILITIES = """
            version: 1
            metrics:
              branch_coverage: { enabled: false }
              mutation_score: { enabled: false }
              performance: { enabled: false }
              cyclomatic_complexity: { enabled: false }
              api_contract: { enabled: false }
              accessibility: { enabled: false }
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

    private static final String REASON = "上流に修正版が未提供。リバースプロキシ側で該当パスを遮断済み（PR #456）";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired MeasurementRepository measurements;
    @Autowired FindingRepository findings;
    @Autowired WaiverRepository waivers;
    @Autowired RepositorySummaryRepository summaries;
    @Autowired AuditLogRepository auditLogs;
    @Autowired ArtifactStore artifactStore;
    @Autowired JobEnqueuer enqueuer;
    @Autowired JobWorker worker;

    private MockMvcTester mvc;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "admin-user",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        users.save(new UserAccount(Uuid7.generate(), "viewer-user",
                UserRole.VIEWER, UserStatus.ACTIVE, admin.getId()));
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(), "acme",
                "web-app", admin.getId())).getId();
        mvc = TestSessions.tester(context);
    }

    @Test
    void 違反を免除すると判定から除外され記録と再評価が残る() {
        Run run = evaluatedRun(Instant.now().minus(Duration.ofHours(1)));
        assertThat(run.getVerdict()).isEqualTo(Verdict.FAIL);
        String fingerprint = findings.findByRunId(run.getId()).getFirst().getFingerprint();

        MvcTestResult created = createWaiver("FINDING", fingerprint, REASON,
                Instant.now().plus(Duration.ofDays(30)));
        assertThat(created).hasStatus(201)
                .bodyJson()
                .satisfies(json -> {
                    // 登録時点の違反の見出しを残す。fingerprint だけでは何を見逃したか読めない
                    json.assertThat().extractingPath("$.title").asString()
                            .contains("example-lib");
                    json.assertThat().extractingPath("$.createdByLogin").isEqualTo("admin-user");
                    json.assertThat().extractingPath("$.reasonCategoryLabel").isEqualTo("修正版未提供");
                });

        // 登録と同時に積まれた再評価を実行する
        worker.poll();

        Run reevaluated = runs.findById(run.getId()).orElseThrow();
        assertThat(reevaluated.getVerdict()).isEqualTo(Verdict.PASS);
        // 再評価の直前の判定を残す（通知の遷移判定の起点）
        assertThat(reevaluated.getPreviousVerdict()).isEqualTo(Verdict.FAIL);
        Measurement vulnerability = measurementOf(run, "M-06");
        assertThat(vulnerability.getStatus()).isEqualTo(MeasurementStatus.PASS);
        assertThat(vulnerability.getReason()).contains("免除中 1 件を除く");

        // 免除は解決ではない。違反は一覧に残り、免除の印が付く
        assertThat(findings.findByRunId(run.getId())).singleElement()
                .satisfies(f -> assertThat(f.getWaiverId()).isNotNull());
        var summary = summaries.findById(repositoryId).orElseThrow();
        assertThat(summary.getActiveWaiverCount()).isEqualTo(1);
        assertThat(summary.getOpenHighCount()).isZero();

        assertThat(mvc.get().uri("/api/v1/runs/{id}/findings", run.getId())
                .with(as("viewer-user", "VIEWER")))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.items[0].waiver.reason").isEqualTo(REASON);
                    json.assertThat().extractingPath("$.items[0].waiver.status").isEqualTo("ACTIVE");
                });
        assertThat(auditLogs.findAll()).extracting(l -> l.getAction()).contains("WAIVER_CREATED");

        // 失効させると次の判定から再び数える
        String waiverId = JsonPath.read(body(created), "$.waiverId");
        assertThat(mvc.delete().uri("/api/v1/waivers/{id}", waiverId)
                .with(as("admin-user", "ADMIN")))
                .hasStatus(204);
        worker.poll();
        assertThat(runs.findById(run.getId()).orElseThrow().getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(auditLogs.findAll()).extracting(l -> l.getAction()).contains("WAIVER_REVOKED");
    }

    @Test
    void 免除の登録には理由と期限の制約がある() {
        Run run = evaluatedRun(Instant.now().minus(Duration.ofHours(1)));
        String fingerprint = findings.findByRunId(run.getId()).getFirst().getFingerprint();

        // 「対応済み」のような実質のない理由を防ぐ
        assertThat(createWaiver("FINDING", fingerprint, "対応済み", Instant.now().plus(Duration.ofDays(30))))
                .hasStatus(400)
                .bodyJson().extractingPath("$.violations[0].field").isEqualTo("reason");
        assertThat(createWaiver("FINDING", fingerprint, REASON, Instant.now().plus(Duration.ofDays(91))))
                .hasStatus(422)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("WAIVER_EXPIRY_TOO_FAR");
        assertThat(createWaiver("FINDING", fingerprint, REASON, Instant.now().minus(Duration.ofDays(1))))
                .hasStatus(400);
        // 存在しない違反は免除できない
        assertThat(createWaiver("FINDING", "0".repeat(64), REASON, Instant.now().plus(Duration.ofDays(30))))
                .hasStatus(404);

        assertThat(createWaiver("FINDING", fingerprint, REASON, Instant.now().plus(Duration.ofDays(30))))
                .hasStatus(201);
        assertThat(createWaiver("FINDING", fingerprint, REASON, Instant.now().plus(Duration.ofDays(30))))
                .hasStatus(409)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("WAIVER_ALREADY_EXISTS");

        // 閲覧者は一覧を見られるが登録はできない
        assertThat(mvc.get().uri("/api/v1/waivers").with(as("viewer-user", "VIEWER")))
                .hasStatusOk()
                .bodyJson().extractingPath("$.activeCount").isEqualTo(1);
        assertThat(mvc.post().uri("/api/v1/waivers").with(as("viewer-user", "VIEWER"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"repositoryId": "%s", "scope": "METRIC", "metricId": "M-06",
                         "reasonCategory": "PLANNED", "reason": "%s", "expiresAt": "%s"}
                        """.formatted(repositoryId, REASON, Instant.now().plus(Duration.ofDays(3)))))
                .hasStatus(403);
    }

    @Test
    void 指標の免除は判定を参考値にして合否に影響させない() {
        Run run = evaluatedRun(Instant.now().minus(Duration.ofHours(1)));

        assertThat(createWaiver("METRIC", null, REASON, Instant.now().plus(Duration.ofDays(14))))
                .hasStatus(201);
        worker.poll();

        assertThat(runs.findById(run.getId()).orElseThrow().getVerdict()).isEqualTo(Verdict.PASS);
        Measurement vulnerability = measurementOf(run, "M-06");
        assertThat(vulnerability.getStatus()).isEqualTo(MeasurementStatus.REFERENCE);
        assertThat(vulnerability.getReason()).contains("指標全体が免除されています")
                .contains("本来の判定: FAIL");
    }

    @Test
    void 期限切れの免除は日次バッチで無効化され再び数えられる() {
        Run run = evaluatedRun(Instant.now().minus(Duration.ofHours(1)));
        String fingerprint = findings.findByRunId(run.getId()).getFirst().getFingerprint();
        assertThat(createWaiver("FINDING", fingerprint, REASON, Instant.now().plus(Duration.ofDays(30))))
                .hasStatus(201);
        worker.poll();
        assertThat(runs.findById(run.getId()).orElseThrow().getVerdict()).isEqualTo(Verdict.PASS);

        jdbc.update("UPDATE waivers SET created_at = ?, expires_at = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(31))),
                Timestamp.from(Instant.now().minusSeconds(60)));
        enqueuer.enqueue(JobType.EXPIRE_WAIVERS, "test", Map.of());
        worker.poll();   // 期限切れ処理
        worker.poll();   // 積まれた再評価

        assertThat(waivers.findAll()).singleElement()
                .satisfies(w -> assertThat(w.getStatus()).isEqualTo(WaiverStatus.EXPIRED));
        assertThat(runs.findById(run.getId()).orElseThrow().getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(auditLogs.findAll()).filteredOn(l -> l.getAction().equals("WAIVER_EXPIRED"))
                .singleElement()
                .satisfies(l -> assertThat(l.getActorLogin()).isEqualTo("system"));
    }

    @Test
    void 管理者は再評価を依頼でき成果物が消えていれば拒否される() {
        Run run = evaluatedRun(Instant.now().minus(Duration.ofHours(1)));

        assertThat(mvc.post().uri("/api/v1/runs/{id}/reevaluate", run.getId())
                .with(as("viewer-user", "VIEWER")))
                .hasStatus(403);
        assertThat(mvc.post().uri("/api/v1/runs/{id}/reevaluate", run.getId())
                .with(as("admin-user", "ADMIN")))
                .hasStatus(202)
                .bodyJson().extractingPath("$.jobId").isNotNull();
        worker.poll();
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.EVALUATED);

        jdbc.update("UPDATE artifacts SET deleted_at = now()");
        assertThat(mvc.post().uri("/api/v1/runs/{id}/reevaluate", run.getId())
                .with(as("admin-user", "ADMIN")))
                .hasStatus(409)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("ARTIFACTS_DELETED");
    }

    @Test
    void 保持期間を過ぎた成果物とRunは日次バッチで削除される() {
        Run old = evaluatedRun(Instant.now().minus(Duration.ofDays(800)));
        Run recent = evaluatedRun(Instant.now().minus(Duration.ofDays(100)));
        jdbc.update("UPDATE artifacts SET uploaded_at = now() - interval '100 days'");
        String recentKey = artifacts.findByRunId(recent.getId()).getFirst().getStorageKey();

        enqueuer.enqueue(JobType.CLEANUP_RETENTION, "test", Map.of());
        worker.poll();

        // 2 年を過ぎた Run は判定結果ごと消える
        assertThat(runs.findById(old.getId())).isEmpty();
        // 90 日を過ぎた成果物は実体だけ消し、削除した事実を残す
        assertThat(artifacts.findByRunId(recent.getId()))
                .allSatisfy(a -> assertThat(a.getDeletedAt()).isNotNull());
        assertThat(artifactStore.exists(recentKey)).isFalse();
        assertThat(runs.findById(recent.getId())).isPresent();
    }

    @Test
    void finalizeされないまま滞留したRunは終端にされる() {
        Run stale = runs.save(new Run(Uuid7.generate(), repositoryId, "f".repeat(40), "main",
                RunnerType.SELF_HOSTED, "ci", Instant.now().minus(Duration.ofDays(2)), 1));
        jdbc.update("UPDATE runs SET created_at = now() - interval '2 days' WHERE id = ?",
                stale.getId());

        enqueuer.enqueue(JobType.ABANDON_STALE_RUNS, "test", Map.of());
        worker.poll();

        assertThat(runs.findById(stale.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.ABANDONED);
    }

    private MvcTestResult createWaiver(String scope, String fingerprint, String reason,
                                       Instant expiresAt) {
        String body = """
                {"repositoryId": "%s", "scope": "%s", "metricId": "M-06", %s
                 "reasonCategory": "NO_FIX_AVAILABLE", "reason": "%s", "expiresAt": "%s"}
                """.formatted(repositoryId, scope,
                fingerprint == null ? "" : "\"fingerprint\": \"%s\",".formatted(fingerprint),
                reason, expiresAt);
        return mvc.post().uri("/api/v1/waivers").with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    /** High の脆弱性 1 件を含む Run を取り込み、判定ジョブまで流す。 */
    private Run evaluatedRun(Instant measuredAt) {
        Run run = new Run(Uuid7.generate(), repositoryId,
                String.format("%040x", Math.abs(measuredAt.hashCode())), "main",
                RunnerType.SELF_HOSTED, "github-actions", measuredAt, 1);
        run.finalizeIngest();
        runs.save(run);
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", ONLY_VULNERABILITIES);
        attach(run, ArtifactType.SARIF, "trivy.sarif", TRIVY_HIGH);
        enqueuer.enqueue(JobType.EVALUATE_RUN, run.getId().toString(),
                Map.of("runId", run.getId().toString()));
        worker.poll();
        return runs.findById(run.getId()).orElseThrow();
    }

    private void attach(Run run, ArtifactType type, String filename, String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(), null, null, null));
    }

    private Measurement measurementOf(Run run, String metricId) {
        return measurements.findByRunId(run.getId()).stream()
                .filter(m -> m.getMetricId().equals(metricId)).findFirst().orElseThrow();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
