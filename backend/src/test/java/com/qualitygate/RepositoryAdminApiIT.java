package com.qualitygate;

import com.jayway.jsonpath.JsonPath;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.AuditLogRepository;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.IngestTokenRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.platform.id.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static com.qualitygate.TestSessions.as;
import static org.assertj.core.api.Assertions.assertThat;

/** リポジトリ管理（S-08）・Ingest Token・設定（S-06）の API。 */
@SpringBootTest
@AbstractIntegrationTest
class RepositoryAdminApiIT {

    @Autowired WebApplicationContext context;
    @Autowired UserAccountRepository users;
    @Autowired IngestTokenRepository tokens;
    @Autowired GateConfigRepository configs;
    @Autowired AuditLogRepository auditLogs;
    @Autowired JdbcTemplate jdbc;

    private MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "admin-user",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        users.save(new UserAccount(Uuid7.generate(), "viewer-user",
                UserRole.VIEWER, UserStatus.ACTIVE, admin.getId()));
        mvc = TestSessions.tester(context);
    }

    @Test
    void 管理者はリポジトリを登録でき一覧と詳細に現れる() {
        String repositoryId = createRepository();

        assertThat(mvc.get().uri("/api/v1/repositories").with(as("viewer-user", "VIEWER")))
                .hasStatusOk()
                .bodyJson().extractingPath("$.items[0].fullName").isEqualTo("acme/web-app");

        assertThat(mvc.post().uri("/api/v1/repositories/{id}/components", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"backend\",\"language\":\"java\",\"pathPatterns\":[\"backend/**\"]}"))
                .hasStatus(204);

        assertThat(mvc.get().uri("/api/v1/repositories/{id}", repositoryId)
                .with(as("viewer-user", "VIEWER")))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.repository.defaultBranch").isEqualTo("develop");
                    json.assertThat().extractingPath("$.components[0].pathPatterns[0]")
                            .isEqualTo("backend/**");
                    // まだ Run が無い。0 件の判定を捏造しない
                    json.assertThat().extractingPath("$.latestRun").isNull();
                    json.assertThat().extractingPath("$.freshness.fullMeasurementIntervalDays")
                            .isEqualTo(7);
                });
        assertThat(auditLogs.findAll()).extracting(l -> l.getAction())
                .containsExactlyInAnyOrder("REPOSITORY_CREATED", "COMPONENT_DEFINED");
    }

    @Test
    void 同じリポジトリは大文字小文字を問わず二重に登録できない() {
        createRepository();

        assertThat(mvc.post().uri("/api/v1/repositories").with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"owner\":\"ACME\",\"name\":\"Web-App\"}"))
                .hasStatus(409)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("REPOSITORY_ALREADY_EXISTS");
    }

    @Test
    void 閲覧者はリポジトリを登録できない() {
        assertThat(mvc.post().uri("/api/v1/repositories").with(as("viewer-user", "VIEWER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"owner\":\"acme\",\"name\":\"web-app\"}"))
                .hasStatus(403);
    }

    @Test
    void 発行したトークンで取り込めて失効後は使えない() {
        String repositoryId = createRepository();

        MvcTestResult issued = mvc.post().uri("/api/v1/repositories/{id}/ingest-tokens", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"GitHub Actions\"}")
                .exchange();
        assertThat(issued).hasStatus(201);
        String token = JsonPath.read(body(issued), "$.token");
        String tokenId = JsonPath.read(body(issued), "$.tokenId");
        assertThat(token).matches("qg_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}");
        // 保存するのはハッシュだけ。平文は DB にも監査ログにも残さない
        assertThat(tokens.findAll()).singleElement()
                .satisfies(t -> assertThat(t.getTokenHash()).doesNotContain(token));
        assertThat(auditLogs.findAll()).allSatisfy(log ->
                assertThat(String.valueOf(log.getAfterValue())).doesNotContain(token));

        // 一覧には平文を出さない
        assertThat(mvc.get().uri("/api/v1/repositories/{id}/ingest-tokens", repositoryId)
                .with(as("admin-user", "ADMIN")))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.items[0].description").isEqualTo("GitHub Actions");
                    json.assertThat().doesNotHavePath("$.items[0].token");
                });

        assertThat(createRun(token)).hasStatus(201);

        assertThat(mvc.delete().uri("/api/v1/ingest-tokens/{id}", tokenId)
                .with(as("admin-user", "ADMIN")))
                .hasStatus(204);
        assertThat(createRun(token)).hasStatus(401);
    }

    @Test
    void 無効化したリポジトリには取り込めない() {
        String repositoryId = createRepository();
        MvcTestResult issued = mvc.post().uri("/api/v1/repositories/{id}/ingest-tokens", repositoryId)
                .with(as("admin-user", "ADMIN")).exchange();
        String token = JsonPath.read(body(issued), "$.token");

        assertThat(mvc.patch().uri("/api/v1/repositories/{id}", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
                .hasStatusOk();

        assertThat(createRun(token)).hasStatus(403);
    }

    @Test
    void UIから設定を保存でき検証エラーは行番号付きで返る() {
        String repositoryId = createRepository();

        assertThat(mvc.get().uri("/api/v1/repositories/{id}/config", repositoryId)
                .with(as("viewer-user", "VIEWER")))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.current").isNull();
                    json.assertThat().extractingPath("$.editable").isEqualTo(true);
                    json.assertThat().extractingPath("$.validation.valid").isEqualTo(true);
                });

        assertThat(mvc.put().uri("/api/v1/repositories/{id}/config", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawYaml\":\"version: 1\\nmetrics:\\n  branch_coverage:\\n    threshold: \\\"75%\\\"\\n\"}"))
                .hasStatus(422)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.errorCode").isEqualTo("CONFIG_VALIDATION_FAILED");
                    json.assertThat().extractingPath("$.errors[0].line").isEqualTo(4);
                });

        assertThat(mvc.put().uri("/api/v1/repositories/{id}/config", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawYaml\":\"version: 1\\nmetrics:\\n  branch_coverage:\\n    threshold: 80\\n\"}"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.current.version").isEqualTo(1);
                    json.assertThat().extractingPath("$.current.sourceType").isEqualTo("UI");
                });
        assertThat(auditLogs.findAll()).extracting(l -> l.getAction()).contains("CONFIG_UPDATED");

        // 閲覧者は編集できない
        assertThat(mvc.put().uri("/api/v1/repositories/{id}/config", repositoryId)
                .with(as("viewer-user", "VIEWER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawYaml\":\"version: 1\\n\"}"))
                .hasStatus(403);
    }

    @Test
    void ファイルで管理されている設定はUIから編集できない() {
        String repositoryId = createRepository();
        jdbc.update("""
                INSERT INTO gate_configs (id, repository_id, version, source_type, content_hash,
                                          raw_yaml, parsed)
                VALUES (?, ?::uuid, 1, 'FILE', 'h', 'version: 1', '{}'::jsonb)
                """, Uuid7.generate(), repositoryId);

        assertThat(mvc.put().uri("/api/v1/repositories/{id}/config", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawYaml\":\"version: 1\\n\"}"))
                .hasStatus(409)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("CONFIG_MANAGED_BY_FILE");
    }

    private String createRepository() {
        MvcTestResult created = mvc.post().uri("/api/v1/repositories")
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"owner\":\"acme\",\"name\":\"web-app\",\"defaultBranch\":\"develop\"}")
                .exchange();
        assertThat(created).hasStatus(201);
        return JsonPath.read(body(created), "$.repositoryId");
    }

    private MvcTestResult createRun(String token) {
        return mvc.post().uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"repository": "acme/web-app",
                         "commitSha": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
                         "branch": "develop", "runnerType": "self-hosted",
                         "triggeredBy": "github-actions", "measuredAt": "2026-09-21T02:10:00Z"}
                        """)
                .exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
