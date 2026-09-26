package com.qualitygate;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.job.JobEnqueuer;
import com.qualitygate.job.JobWorker;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.platform.storage.StoredArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.qualitygate.TestSessions.as;
import static org.assertj.core.api.Assertions.assertThat;

/** Run の再評価（手動）と、日次の後始末（保持期間の削除・滞留した Run の終端化）。 */
@SpringBootTest
@AbstractIntegrationTest
class RunMaintenanceIT {

    /** M-06 だけを判定する設定。他の指標の成果物を用意せずに判定まで流す。 */
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

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired ArtifactRecordRepository artifacts;
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
                "ci", Instant.now().minus(Duration.ofDays(2)), 1));
        jdbc.update("UPDATE runs SET created_at = now() - interval '2 days' WHERE id = ?",
                stale.getId());

        enqueuer.enqueue(JobType.ABANDON_STALE_RUNS, "test", Map.of());
        worker.poll();

        assertThat(runs.findById(stale.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.ABANDONED);
    }

    /** High の脆弱性 1 件を含む Run を取り込み、判定ジョブまで流す。 */
    private Run evaluatedRun(Instant measuredAt) {
        Run run = new Run(Uuid7.generate(), repositoryId,
                String.format("%040x", Math.abs(measuredAt.hashCode())), "main",
                "github-actions", measuredAt, 1);
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
}
