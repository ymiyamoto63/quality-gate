package com.qualitygate;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.RunnerType;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.platform.id.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** README 用のバッジ（FR-08-5）。Cookie なしで取得でき、既定ブランチの最新の合否だけを返す。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AbstractIntegrationTest
class BadgeIT {

    @Value("${local.server.port}")
    int port;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired JobRepository jobs;

    private RestClient client;
    private MonitoredRepository repository;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        jobs.deleteAll();
        runs.deleteAll();
        repositories.deleteAll();
        users.deleteAll();
        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        repository = repositories.save(new MonitoredRepository(
                Uuid7.generate(), "ymiyamoto63", "quality-gate", admin.getId()));
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    @Test
    void 既定ブランチの最新の判定をログインなしで返す() {
        evaluated("main", Verdict.FAIL, "2026-09-20T00:00:00Z");
        evaluated("main", Verdict.PASS, "2026-09-21T00:00:00Z");
        // 既定ブランチ以外の Run は、より新しくてもバッジに影響しない
        evaluated("feature/x", Verdict.FAIL, "2026-09-22T00:00:00Z");

        ResponseEntity<String> response = get("/badges/ymiyamoto63/quality-gate.svg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("image/svg+xml");
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=300");
        assertThat(response.getBody()).contains("quality gate: passing").doesNotContain("M-0");
    }

    @Test
    void 不合格と警告つき合格を区別する() {
        evaluated("main", Verdict.PASS_WITH_WARNINGS, "2026-09-21T00:00:00Z");
        assertThat(get("/badges/ymiyamoto63/quality-gate.svg").getBody()).contains("passing with warnings");

        evaluated("main", Verdict.FAIL, "2026-09-22T00:00:00Z");
        assertThat(get("/badges/ymiyamoto63/quality-gate.svg").getBody()).contains("quality gate: failing");
    }

    @Test
    void 未登録と無効化と未判定は区別せずunknownにする() {
        assertThat(get("/badges/ymiyamoto63/quality-gate.svg").getBody()).contains("unknown");
        assertThat(get("/badges/someone/secret-repo.svg").getBody()).contains("unknown");

        evaluated("main", Verdict.PASS, "2026-09-21T00:00:00Z");
        repository.setEnabled(false);
        repositories.save(repository);
        assertThat(get("/badges/ymiyamoto63/quality-gate.svg").getBody()).contains("unknown");
    }

    private void evaluated(String branch, Verdict verdict, String measuredAt) {
        // Run は（リポジトリ, コミット, 試行）で一意のため、コミットを Run ごとに変える
        String commit = (Uuid7.generate().toString() + Uuid7.generate()).replace("-", "").substring(0, 40);
        Run run = new Run(Uuid7.generate(), repository.getId(), commit, branch,
                RunnerType.SELF_HOSTED, "it", Instant.parse(measuredAt), 1);
        run.markEvaluated(verdict, Completeness.FULL, Instant.parse(measuredAt));
        runs.save(run);
    }

    private ResponseEntity<String> get(String path) {
        return client.get().uri(path).retrieve().toEntity(String.class);
    }
}
