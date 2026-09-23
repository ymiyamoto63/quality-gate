package com.qualitygate;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.entity.Waiver;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.JobStatus;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.model.RunnerType;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.model.WaiverReasonCategory;
import com.qualitygate.domain.model.WaiverScope;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.NotificationRecordRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.domain.repo.WaiverRepository;
import com.qualitygate.job.JobEnqueuer;
import com.qualitygate.job.JobWorker;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.platform.storage.StoredArtifact;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.qualitygate.TestSessions.as;
import static org.assertj.core.api.Assertions.assertThat;

/** メール通知（FR-11）。SMTP は偽のサーバで受ける（application-test.yml の spring.mail）。 */
@SpringBootTest
@AbstractIntegrationTest
class NotificationIT {

    private static final int SMTP_PORT = 38025;

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

    private static final String TRIVY_CLEAN = """
            { "version": "2.1.0", "runs": [{ "tool": { "driver": { "name": "Trivy", "rules": [] }},
              "results": [] }]}
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
    @Autowired NotificationRecordRepository notifications;
    @Autowired WaiverRepository waivers;
    @Autowired JobRepository jobs;
    @Autowired ArtifactStore artifactStore;
    @Autowired JobEnqueuer enqueuer;
    @Autowired JobWorker worker;

    private FakeSmtpServer smtp;
    private MockMvcTester mvc;
    private UserAccount admin;
    private UUID repositoryId;
    private int commit;

    @BeforeEach
    void setUp() throws IOException {
        IntegrationCleanup.deleteAll(jdbc);
        admin = users.save(new UserAccount(Uuid7.generate(), "admin-user",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(), "acme",
                "web-app", admin.getId())).getId();
        mvc = TestSessions.tester(context);
        smtp = new FakeSmtpServer(SMTP_PORT);
    }

    @AfterEach
    void tearDown() throws IOException {
        smtp.close();
    }

    @Test
    void 合格から不合格に変わったときだけメールで通知する() {
        configure("{\"condition\":\"TRANSITION\",\"emailRecipients\":[\"qa@example.com\",\"dev@example.com\"]}");

        evaluate("main", null, TRIVY_CLEAN);
        assertThat(smtp.received()).isEmpty();

        Run failed = evaluate("main", null, TRIVY_HIGH);
        assertThat(smtp.received()).extracting(FakeSmtpServer.Mail::to)
                .containsExactlyInAnyOrder("qa@example.com", "dev@example.com");
        assertThat(decoded(smtp.received().getFirst()))
                .contains("■ 不合格")
                .contains("● 合格 → ■ 不合格")
                .contains("重大・高 脆弱性件数")
                .contains("/runs/" + failed.getId());

        // 不合格が続く間は送らない。毎回送ると読まれなくなる
        evaluate("main", null, TRIVY_HIGH);
        assertThat(smtp.received()).hasSize(2);
        assertThat(notifications.findAll()).extracting(n -> n.getStatus()).containsOnly("SENT");
    }

    @Test
    void 監視対象ブランチ以外の判定は通知しない() {
        configure("{\"condition\":\"EVERY_RUN\",\"emailRecipients\":[\"qa@example.com\"]}");

        evaluate("feature/x", 12, TRIVY_HIGH);

        assertThat(smtp.received()).isEmpty();
    }

    @Test
    void 通知設定は管理者だけが変更でき監査ログに残る() {
        assertThat(mvc.put().uri("/api/v1/repositories/{id}/notification-settings", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"condition\":\"FAIL_ONLY\",\"emailRecipients\":[\"qa@example.com\"]}"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.condition").isEqualTo("FAIL_ONLY");
                    json.assertThat().extractingPath("$.emailRecipients[0]").isEqualTo("qa@example.com");
                    json.assertThat().extractingPath("$.emailAvailable").isEqualTo(true);
                });
        assertThat(jdbc.queryForObject("SELECT action FROM audit_logs", String.class))
                .isEqualTo("NOTIFICATION_SETTINGS_UPDATED");

        assertThat(mvc.put().uri("/api/v1/repositories/{id}/notification-settings", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"emailRecipients\":[\"not-an-address\"]}"))
                .hasStatus(400);
    }

    @Test
    void SMTPの一時的な障害は再試行される() {
        configure("{\"condition\":\"EVERY_RUN\",\"emailRecipients\":[\"qa@example.com\"]}");
        smtp.failWith("451 Temporary failure");

        evaluate("main", null, TRIVY_CLEAN);

        assertThat(jobs.findAll()).filteredOn(j -> j.getType() == JobType.SEND_NOTIFICATION)
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
                    assertThat(job.getAttempts()).isEqualTo(1);
                });
        assertThat(notifications.findAll()).singleElement()
                .satisfies(n -> assertThat(n.getStatus()).isEqualTo("FAILED"));
    }

    @Test
    void 期限の近い免除を一度だけ知らせる() {
        configure("{\"emailRecipients\":[\"qa@example.com\"]}");
        Instant now = Instant.now();
        waivers.save(new Waiver(Uuid7.generate(), repositoryId, WaiverScope.METRIC, "M-06", null,
                "重大・高 脆弱性件数（指標全体）", WaiverReasonCategory.PLANNED,
                "来週のリリースで依存を更新する。PR #789 で対応予定", admin.getId(), now,
                now.plus(Duration.ofDays(3))));

        enqueuer.enqueue(JobType.EXPIRE_WAIVERS, "test-1", Map.of());
        worker.poll();
        // 翌日も同じ免除については送らない
        enqueuer.enqueue(JobType.EXPIRE_WAIVERS, "test-2", Map.of());
        worker.poll();

        assertThat(smtp.received()).singleElement().satisfies(mail -> assertThat(decoded(mail))
                .contains("7 日以内に切れます").contains("重大・高 脆弱性件数（指標全体）"));
    }

    private void configure(String json) {
        assertThat(mvc.put().uri("/api/v1/repositories/{id}/notification-settings", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .hasStatusOk();
    }

    /** 1 つの Run を取り込み、判定と通知のジョブまで流す。 */
    private Run evaluate(String branch, Integer pullRequest, String sarif) {
        Instant measuredAt = Instant.now().minus(Duration.ofHours(10)).plusSeconds(commit);
        Run run = new Run(Uuid7.generate(), repositoryId, "%040x".formatted(++commit), branch,
                RunnerType.SELF_HOSTED, "github-actions", measuredAt, 1);
        run.setPullRequestNumber(pullRequest);
        run.finalizeIngest();
        runs.save(run);
        attach(run, ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", ONLY_VULNERABILITIES);
        attach(run, ArtifactType.SARIF, "trivy.sarif", sarif);
        enqueuer.enqueue(JobType.EVALUATE_RUN, run.getId().toString(),
                Map.of("runId", run.getId().toString()));
        worker.poll();   // 判定
        worker.poll();   // 通知
        return runs.findById(run.getId()).orElseThrow();
    }

    private void attach(Run run, ArtifactType type, String filename, String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(), null, null, null));
    }

    /** 件名（MIME エンコード）と本文（base64 など）を読める形に戻す。 */
    private static String decoded(FakeSmtpServer.Mail mail) {
        try {
            var message = new jakarta.mail.internet.MimeMessage(
                    jakarta.mail.Session.getInstance(new java.util.Properties()),
                    new ByteArrayInputStream(mail.content().getBytes(StandardCharsets.UTF_8)));
            return MimeUtility.decodeText(message.getSubject()) + "\n" + message.getContent();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
