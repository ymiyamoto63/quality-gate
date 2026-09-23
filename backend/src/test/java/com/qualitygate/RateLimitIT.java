package com.qualitygate;

import com.qualitygate.domain.entity.IngestToken;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.IngestTokenRepository;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.ingest.security.IngestTokenAuthenticationFilter;
import com.qualitygate.platform.id.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * レート制限（docs/initial/07-api-design.md 8 章）を実際の HTTP で確かめる。上限は小さくしてある。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "quality-gate.rate-limit.enabled=true",
        "quality-gate.rate-limit.ingest-per-minute=3",
        "quality-gate.rate-limit.query-per-minute=2"})
@AbstractIntegrationTest
class RateLimitIT {

    private static final String TOKEN = "qg_ratelim_0123456789abcdef0123456789abcdef";
    private static final String OTHER_TOKEN = "qg_ratelm2_0123456789abcdef0123456789abcdef";

    @Value("${local.server.port}")
    int port;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired IngestTokenRepository tokens;
    @Autowired RunRepository runs;
    @Autowired JobRepository jobs;

    private RestClient client;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        jobs.deleteAll();
        runs.deleteAll();
        tokens.deleteAll();
        repositories.deleteAll();
        users.deleteAll();

        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        MonitoredRepository repository = repositories.save(new MonitoredRepository(
                Uuid7.generate(), "ymiyamoto63", "quality-gate", admin.getId()));
        tokens.save(new IngestToken(Uuid7.generate(), repository.getId(), "ratelim",
                IngestTokenAuthenticationFilter.sha256(TOKEN), "IT 用", admin.getId()));
        tokens.save(new IngestToken(Uuid7.generate(), repository.getId(), "ratelm2",
                IngestTokenAuthenticationFilter.sha256(OTHER_TOKEN), "IT 用", admin.getId()));

        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    @Test
    void IngestAPIはトークンごとに上限を超えると429とRetryAfterを返す() {
        for (int i = 0; i < 3; i++) {
            assertThat(status(TOKEN)).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        ResponseEntity<Map> rejected = request(TOKEN);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(rejected.getBody()).containsEntry("errorCode", "RATE_LIMITED").containsEntry("status", 429);
        // 別のトークンの枠は減っていない
        assertThat(status(OTHER_TOKEN)).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void 参照APIは未ログインならIPごとに制限する() {
        assertThat(client.get().uri("/api/v1/me").retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.get().uri("/api/v1/me").retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(client.get().uri("/api/v1/me").retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // 画面の静的ファイルと監視は制限しない
        assertThat(client.get().uri("/actuator/health").retrieve().toEntity(String.class).getStatusCode())
                .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private HttpStatus status(String token) {
        return HttpStatus.valueOf(request(token).getStatusCode().value());
    }

    private ResponseEntity<Map> request(String token) {
        return client.get()
                .uri("/api/v1/runs/" + Uuid7.generate() + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toEntity(Map.class);
    }
}
