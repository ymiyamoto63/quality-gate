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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可観測性（docs/initial/05-architecture.md 10 章）: 相関 ID。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AbstractIntegrationTest
class ObservabilityIT {

    private static final String TOKEN = "qg_observe_0123456789abcdef0123456789abcdef";

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
        tokens.save(new IngestToken(Uuid7.generate(), repository.getId(), "observe",
                IngestTokenAuthenticationFilter.sha256(TOKEN), "IT 用", admin.getId()));
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    @Test
    void エラー応答のtraceIdは要求のIDと一致する() {
        ResponseEntity<Map> response = client.post().uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "trace-me-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", "ymiyamoto63/quality-gate"))
                .retrieve().toEntity(Map.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-me-1");
        assertThat(response.getBody()).containsEntry("traceId", "trace-me-1");
    }
}
