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
import com.qualitygate.job.JobMetrics;
import com.qualitygate.job.JobWorker;
import com.qualitygate.platform.id.Uuid7;
import io.micrometer.core.instrument.MeterRegistry;
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
 * 可観測性（docs/initial/05-architecture.md 10 章）: 相関 ID と qg.* のメトリクス。
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
    @Autowired MeterRegistry registry;
    @Autowired JobMetrics jobMetrics;
    @Autowired JobWorker worker;

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

    @Test
    void 取り込みと判定とジョブのメトリクスを記録する() {
        double created = count("qg.ingest.runs", "result", "created");
        double finalized = count("qg.ingest.runs", "result", "finalized");

        ResponseEntity<Map> run = client.post().uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", "ymiyamoto63/quality-gate",
                        "commitSha", "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", "branch", "main",
                        "triggeredBy", "it", "measuredAt", "2026-09-24T00:00:00Z"))
                .retrieve().toEntity(Map.class);
        String runId = (String) run.getBody().get("runId");
        client.post().uri("/api/v1/runs/" + runId + "/finalize")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .retrieve().toBodilessEntity();

        assertThat(count("qg.ingest.runs", "result", "created")).isEqualTo(created + 1);
        assertThat(count("qg.ingest.runs", "result", "finalized")).isEqualTo(finalized + 1);

        jobMetrics.refresh();
        assertThat(registry.get("qg.jobs.pending").tag("type", "EVALUATE_RUN").gauge().value()).isEqualTo(1);
        assertThat(registry.get("qg.jobs.dead").gauge().value()).isZero();
        assertThat(registry.find("qg.artifacts.bytes").gauge()).isNotNull();

        long totalBefore = timerCount("total");
        worker.poll();

        assertThat(timerCount("total")).isEqualTo(totalBefore + 1);
        jobMetrics.refresh();
        assertThat(registry.get("qg.jobs.pending").tag("type", "EVALUATE_RUN").gauge().value()).isZero();
    }

    private double count(String name, String tag, String value) {
        var counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private long timerCount(String metricId) {
        var timer = registry.find("qg.evaluation.duration").tag("metric_id", metricId).timer();
        return timer == null ? 0 : timer.count();
    }
}
