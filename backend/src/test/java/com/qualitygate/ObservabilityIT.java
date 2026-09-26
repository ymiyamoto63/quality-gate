package com.qualitygate;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.platform.id.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可観測性（docs/spec/05-architecture.md 10 章）: 相関 ID。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AbstractIntegrationTest
class ObservabilityIT {

    private static final String TOKEN = IntegrationCleanup.INGEST_TOKEN;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    @Value("${local.server.port}")
    int port;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;

    private RestClient client;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        runs.deleteAll();
        repositories.deleteAll();
        users.deleteAll();
        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        repositories.save(new MonitoredRepository(
                Uuid7.generate(), "ymiyamoto63", "quality-gate", admin.getId()));
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    @Test
    void エラー応答のtraceIdは要求のIDと一致する() {
        ResponseEntity<Map<String, Object>> response = client.post().uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "trace-me-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", "ymiyamoto63/quality-gate"))
                .retrieve().toEntity(JSON_OBJECT);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-me-1");
        assertThat(response.getBody()).containsEntry("traceId", "trace-me-1");
    }
}
