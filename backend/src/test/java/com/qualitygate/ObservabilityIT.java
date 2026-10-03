package com.qualitygate;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
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
 * 可観測性（docs/architecture.md 4.3）: 相関 ID。
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
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;

    private RestClient client;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        runs.deleteAll();
        repositories.deleteAll();
        repositories.save(new MonitoredRepository(
                Uuid7.generate(), "ymiyamoto63", "quality-gate"));
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    @Test
    void エラー応答のtraceIdは要求のIDと一致する() {
        ResponseEntity<Map<String, Object>> response = client.post().uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", "ymiyamoto63/quality-gate"))
                .retrieve().toEntity(JSON_OBJECT);

        String requestId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).isNotBlank();
        assertThat(response.getBody()).containsEntry("traceId", requestId);
    }
}
