package com.qualitygate;

import com.qualitygate.domain.entity.IngestToken;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.IngestTokenRepository;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.ingest.security.IngestTokenAuthenticationFilter;
import com.qualitygate.platform.id.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取り込みの縦串（Run 作成 → 成果物 → 確定）を実際の HTTP と DB で検証する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AbstractIntegrationTest
class IngestApiIT {

    private static final String REPOSITORY = "ymiyamoto63/quality-gate";
    private static final String COMMIT = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String TOKEN = "qg_testpfx_0123456789abcdef0123456789abcdef";

    @Value("${local.server.port}")
    int port;

    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired IngestTokenRepository tokens;
    @Autowired RunRepository runs;
    @Autowired RunSkippedMetricRepository skippedMetrics;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired JobRepository jobs;

    private RestClient client;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        jobs.deleteAll();
        artifacts.deleteAll();
        skippedMetrics.deleteAll();
        runs.deleteAll();
        tokens.deleteAll();
        repositories.deleteAll();
        users.deleteAll();

        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        MonitoredRepository repository = repositories.save(new MonitoredRepository(
                Uuid7.generate(), "ymiyamoto63", "quality-gate", admin.getId()));
        repositoryId = repository.getId();
        tokens.save(new IngestToken(Uuid7.generate(), repositoryId, "testpfx",
                IngestTokenAuthenticationFilter.sha256(TOKEN), "IT 用", admin.getId()));

        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    @Test
    void Run作成から確定までが通る() {
        // Run を作成する
        ResponseEntity<Map> created = client.post()
                .uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "repository", REPOSITORY,
                        "commitSha", COMMIT,
                        "branch", "main",
                        "runnerType", "github-hosted",
                        "triggeredBy", "github-actions",
                        "measuredAt", "2026-09-21T02:10:00Z",
                        "skippedMetrics", java.util.List.of(
                                Map.of("metricId", "M-02", "reason", "GitHub ホストランナーのため PIT を実行しない"),
                                Map.of("metricId", "M-06", "reason", "理由なくスキップを申告した場合"))))
                .retrieve().toEntity(Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID runId = UUID.fromString(String.valueOf(created.getBody().get("runId")));
        assertThat(created.getBody()).containsEntry("attempt", 1);
        assertThat(String.valueOf(created.getBody().get("detailUrl"))).endsWith(runId.toString());

        // 許容されない指標のスキップ申告は accepted=false として残る（判定時に ERROR になる）
        assertThat(skippedMetrics.findByKeyRunId(runId))
                .extracting("metricId", "accepted")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M-02", true),
                        org.assertj.core.groups.Tuple.tuple("M-06", false));

        // 成果物をアップロードする
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("<report/>".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "jacoco.xml";
            }
        });
        form.add("type", "jacoco-xml");
        form.add("component", "backend");

        ResponseEntity<Map> uploaded = client.post()
                .uri("/api/v1/runs/{runId}/artifacts", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().toEntity(Map.class);

        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(artifacts.findByRunId(runId)).hasSize(1);

        // 確定する。判定は非同期のため、ここではジョブが積まれることを確認する。
        ResponseEntity<Map> finalized = client.post()
                .uri("/api/v1/runs/{runId}/finalize", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .retrieve().toEntity(Map.class);

        assertThat(finalized.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(finalized.getBody()).containsEntry("status", "FINALIZED");
        assertThat(jobs.count()).isEqualTo(1);

        // 確定後の成果物追加は 409
        ResponseEntity<Map> afterFinalize = client.post()
                .uri("/api/v1/runs/{runId}/artifacts", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().toEntity(Map.class);

        assertThat(afterFinalize.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(afterFinalize.getBody()).containsEntry("errorCode", "RUN_ALREADY_FINALIZED");
    }

    @Test
    void トークンが無ければ拒否される() {
        ResponseEntity<Void> response = client.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", REPOSITORY, "commitSha", COMMIT, "branch", "main",
                        "runnerType", "self-hosted", "triggeredBy", "ci",
                        "measuredAt", "2026-09-21T02:10:00Z"))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 発行元と違うリポジトリへは送信できない() {
        UUID otherOwner = users.findAll().getFirst().getId();
        repositories.save(new MonitoredRepository(Uuid7.generate(), "someone", "other", otherOwner));

        ResponseEntity<Map> response = client.post()
                .uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", "someone/other", "commitSha", COMMIT, "branch", "main",
                        "runnerType", "self-hosted", "triggeredBy", "ci",
                        "measuredAt", "2026-09-21T02:10:00Z"))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("errorCode", "REPOSITORY_MISMATCH");
    }

    @Test
    void 未知の成果物種別は理由つきで拒否される() {
        UUID runId = createRun();

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "unknown.json";
            }
        });
        form.add("type", "unknown-format");

        ResponseEntity<Map> response = client.post()
                .uri("/api/v1/runs/{runId}/artifacts", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).containsEntry("errorCode", "ARTIFACT_TYPE_UNKNOWN");
        // 何をどう直せばよいかを本文に含める
        assertThat(String.valueOf(response.getBody().get("detail"))).contains("unknown-format");
    }

    @Test
    void 性能成果物にenvironmentが無ければ拒否される() {
        UUID runId = createRun();

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "k6-summary.json";
            }
        });
        form.add("type", "k6-summary");

        ResponseEntity<Map> response = client.post()
                .uri("/api/v1/runs/{runId}/artifacts", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).containsEntry("errorCode", "PERFORMANCE_METADATA_MISSING");
    }

    @Test
    void 同一コミットへの再送信は新しいattemptになる() {
        assertThat(attemptOf(createRun())).isEqualTo(1);
        assertThat(attemptOf(createRun())).isEqualTo(2);
    }

    private UUID createRun() {
        ResponseEntity<Map> created = client.post()
                .uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", REPOSITORY, "commitSha", COMMIT, "branch", "main",
                        "runnerType", "self-hosted", "triggeredBy", "ci",
                        "measuredAt", "2026-09-21T02:10:00Z"))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(String.valueOf(created.getBody().get("runId")));
    }

    private int attemptOf(UUID runId) {
        return runs.findById(runId).orElseThrow().getAttempt();
    }
}
