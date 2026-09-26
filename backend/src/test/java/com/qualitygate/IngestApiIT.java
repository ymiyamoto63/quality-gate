package com.qualitygate;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
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
    private static final String TOKEN = IntegrationCleanup.INGEST_TOKEN;

    @Value("${local.server.port}")
    int port;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired RunSkippedMetricRepository skippedMetrics;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired com.qualitygate.platform.storage.ArtifactStore artifactStore;

    private RestClient client;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        artifacts.deleteAll();
        skippedMetrics.deleteAll();
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
                        "triggeredBy", "github-actions",
                        "measuredAt", "2026-09-21T02:10:00Z",
                        "tags", java.util.List.of("v1.2.0", "release/2026-09"),
                        "skippedMetrics", java.util.List.of(
                                Map.of("metricId", "M-02", "reason", "PR の計測では PIT を実行しない"),
                                Map.of("metricId", "M-06", "reason", "理由なくスキップを申告した場合"))))
                .retrieve().toEntity(Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID runId = UUID.fromString(String.valueOf(created.getBody().get("runId")));
        assertThat(created.getBody()).containsEntry("attempt", 1);
        assertThat(String.valueOf(created.getBody().get("detailUrl"))).endsWith(runId.toString());
        assertThat(runs.findById(runId).orElseThrow().getTags()).containsExactly("v1.2.0", "release/2026-09");

        // 申告は受け取るが、受理するかは判定時に決める。
        // 取り込み時点では設定（execution.skippable_metrics）が未解決である。
        assertThat(skippedMetrics.findByKeyRunId(runId))
                .extracting("metricId", "accepted")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("M-02", false),
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

        // 確定すると、その場で判定して結果を返す
        ResponseEntity<Map> finalized = client.post()
                .uri("/api/v1/runs/{runId}/finalize", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .retrieve().toEntity(Map.class);

        assertThat(finalized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finalized.getBody()).containsEntry("status", "EVALUATED")
                .containsKeys("verdict", "completeness", "detailUrl");
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.EVALUATED);

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
                        "triggeredBy", "ci",
                        "measuredAt", "2026-09-21T02:10:00Z"))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void タグ名に使えない文字を含むタグは拒否される() {
        for (String tag : java.util.List.of("a b", "v1..2", "/v1", "v1/", "x:y")) {
            ResponseEntity<Map> response = client.post()
                    .uri("/api/v1/runs")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("repository", REPOSITORY, "commitSha", COMMIT, "branch", "main",
                            "triggeredBy", "ci", "measuredAt", "2026-09-21T02:10:00Z",
                            "tags", java.util.List.of(tag)))
                    .retrieve().toEntity(Map.class);

            assertThat(response.getStatusCode()).as(tag).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(runs.findAll()).isEmpty();
    }

    @Test
    void 違うトークンは拒否される() {
        ResponseEntity<Void> response = client.post()
                .uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN + "x")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", REPOSITORY, "commitSha", COMMIT, "branch", "main",
                        "triggeredBy", "ci",
                        "measuredAt", "2026-09-21T02:10:00Z"))
                .retrieve().toBodilessEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 登録していないリポジトリへは送信できない() {
        ResponseEntity<Map> response = client.post()
                .uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository", "someone/other", "commitSha", COMMIT, "branch", "main",
                        "triggeredBy", "ci",
                        "measuredAt", "2026-09-21T02:10:00Z"))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(runs.findAll()).isEmpty();
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
    void 性能成果物の環境名が無ければ拒否される() {
        UUID runId = createRun();

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "k6-summary.json";
            }
        });
        form.add("type", "k6-summary");
        form.add("metadata", "{\"environment\":{\"runner\":\"self-hosted\"}}");

        ResponseEntity<Map> response = client.post()
                .uri("/api/v1/runs/{runId}/artifacts", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).containsEntry("errorCode", "PERFORMANCE_METADATA_MISSING");
        assertThat(String.valueOf(response.getBody().get("detail"))).contains("environment.name");
    }

    /**
     * 変更範囲と全量の値は比較できない。どちらか分からない値は前回比にもトレンドにも
     * 置き場所がないため、取り込みの時点で拒否して CI のログに残す。
     */
    @Test
    void PITの成果物に実行範囲が無ければ拒否される() {
        ResponseEntity<Map> response = uploadPit(createRun(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).containsEntry("errorCode", "MUTATION_SCOPE_MISSING");
        assertThat(String.valueOf(response.getBody().get("detail")))
                .contains("mutationScope").contains("changed / all");
    }

    @Test
    void PITの実行範囲が選択肢に無ければ拒否される() {
        ResponseEntity<Map> response = uploadPit(createRun(), "{\"mutationScope\":\"diff\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
    }

    @Test
    void metadataがJSONオブジェクトでなければ拒否される() {
        ResponseEntity<Map> response = uploadPit(createRun(), "[\"changed\"]");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("detail"))).contains("JSON オブジェクト");
    }

    @Test
    void 実行範囲つきのPITの成果物は受理される() {
        ResponseEntity<Map> response = uploadPit(createRun(), "{\"mutationScope\":\"changed\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private ResponseEntity<Map> uploadPit(UUID runId, String metadata) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("<mutations/>".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "mutations.xml";
            }
        });
        form.add("type", "pit-xml");
        form.add("component", "backend");
        if (metadata != null) {
            form.add("metadata", metadata);
        }
        return client.post()
                .uri("/api/v1/runs/{runId}/artifacts", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().toEntity(Map.class);
    }

    @Test
    void 同じ種別と名前の成果物を送り直すと置き換える() throws Exception {
        UUID runId = createRun();
        assertThat(upload(runId, "sarif", "report.json", "{\"runs\": [1]}").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<Map> again = upload(runId, "sarif", "report.json", "{\"runs\": [1, 2]}");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(artifacts.findByRunId(runId)).singleElement().satisfies(record -> {
            assertThat(record.getSizeBytes()).isEqualTo(16);
            try (var in = artifactStore.open(record.getStorageKey())) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"runs\": [1, 2]}");
            }
        });
    }

    /** 保存先を成果物ごとに分けているため、種別の違う同名のファイルが互いを上書きしない。 */
    @Test
    void 種別の違う同名の成果物はそれぞれ残る() throws Exception {
        UUID runId = createRun();
        upload(runId, "sarif", "report.json", "{\"runs\": []}");
        upload(runId, "jscpd-json", "report.json", "{\"results\": []}");

        assertThat(artifacts.findByRunId(runId)).hasSize(2).allSatisfy(record -> {
            try (var in = artifactStore.open(record.getStorageKey())) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(content).contains(record.getType().wire().equals("sarif") ? "runs" : "results");
            }
        });
    }

    private ResponseEntity<Map> upload(UUID runId, String type, String filename, String content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        form.add("type", type);
        return client.post()
                .uri("/api/v1/runs/{runId}/artifacts", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().toEntity(Map.class);
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
                        "triggeredBy", "ci",
                        "measuredAt", "2026-09-21T02:10:00Z"))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(String.valueOf(created.getBody().get("runId")));
    }

    private int attemptOf(UUID runId) {
        return runs.findById(runId).orElseThrow().getAttempt();
    }
}
