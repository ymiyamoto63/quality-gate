package com.qualitygate;

import com.qualitygate.domain.entity.IngestToken;
import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.JobStatus;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.IngestTokenRepository;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.ingest.security.IngestTokenAuthenticationFilter;
import com.qualitygate.job.JobWorker;
import com.qualitygate.platform.id.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 判定の後、enforcement が check-run / blocking なら Check Run を出すジョブを積む（Phase 2）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AbstractIntegrationTest
class CheckRunJobIT {

    private static final String TOKEN = "qg_checkrun_0123456789abcdef0123456789abcdef";

    @Value("${local.server.port}")
    int port;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired IngestTokenRepository tokens;
    @Autowired RunRepository runs;
    @Autowired JobRepository jobs;
    @Autowired JobWorker worker;

    private RestClient client;
    private int commit;

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
        tokens.save(new IngestToken(Uuid7.generate(), repository.getId(), "checkrun",
                IngestTokenAuthenticationFilter.sha256(TOKEN), "IT 用", admin.getId()));
        client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { }).build();
    }

    @Test
    void checkRunならCheckRunを出すジョブを積む() {
        submit("check-run");

        assertThat(publishJobs()).singleElement().satisfies(job ->
                assertThat(job.getPayload()).contains("\"enforcement\": \"check-run\""));
    }

    @Test
    void reportOnlyなら積まない() {
        submit("report-only");

        assertThat(publishJobs()).isEmpty();
    }

    private List<Job> publishJobs() {
        return jobs.findAll().stream().filter(job -> job.getType() == JobType.PUBLISH_CHECK_RUN).toList();
    }

    /** Run を作り、設定だけを送って確定し、判定ジョブを 1 回動かす。 */
    private void submit(String enforcement) {
        String sha = String.format("%040x", ++commit);
        Map<?, ?> run = client.post().uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", "ymiyamoto63/quality-gate", "commitSha", sha, "branch", "main",
                        "runnerType", "self-hosted", "triggeredBy", "it", "measuredAt", "2026-09-24T00:00:00Z"))
                .retrieve().body(Map.class);
        String runId = (String) run.get("runId");

        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("""
                version: 1
                enforcement: %s
                metrics:
                  branch_coverage: { enabled: false }
                  mutation_score: { enabled: false }
                  performance: { enabled: false }
                  vulnerabilities: { enabled: false }
                  cyclomatic_complexity: { enabled: false }
                  api_contract: { enabled: false }
                  accessibility: { enabled: false }
                """.formatted(enforcement).getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return ".quality-gate.yml";
            }
        });
        client.post().uri("/api/v1/runs/{id}/artifacts?type=quality-gate-config", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().toBodilessEntity();
        client.post().uri("/api/v1/runs/{id}/finalize", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN).retrieve().toBodilessEntity();

        worker.poll();
        assertThat(jobs.findAll()).filteredOn(job -> job.getType() == JobType.EVALUATE_RUN)
                .allSatisfy(job -> assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED));
    }
}
