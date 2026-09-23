package com.qualitygate;

import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.AuditLogRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.platform.id.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import static com.qualitygate.TestSessions.as;
import static com.qualitygate.TestSessions.withoutCsrf;
import static org.assertj.core.api.Assertions.assertThat;

/** 許可リストと監査ログの管理 API。権限の境界は API 側が担う（画面の制御は防御ではない）。 */
@SpringBootTest
@AbstractIntegrationTest
class AdminApiIT {

    @Autowired WebApplicationContext context;
    @Autowired UserAccountRepository users;
    @Autowired AuditLogRepository auditLogs;
    @Autowired JdbcTemplate jdbc;

    private MockMvcTester mvc;
    private UserAccount admin;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        admin = users.save(new UserAccount(Uuid7.generate(), "admin-user",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        users.save(new UserAccount(Uuid7.generate(), "viewer-user",
                UserRole.VIEWER, UserStatus.ACTIVE, admin.getId()));
        mvc = TestSessions.tester(context);
    }

    @Test
    void 閲覧者は利用者一覧を取得できない() {
        assertThat(mvc.get().uri("/api/v1/users").with(as("viewer-user", "VIEWER")))
                .hasStatus(403)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("FORBIDDEN");
    }

    @Test
    void 管理者は許可リストに利用者を追加でき監査ログに残る() {
        assertThat(mvc.post().uri("/api/v1/users").with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubLogin\":\"new-member\"}"))
                .hasStatus(201)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.githubLogin").isEqualTo("new-member");
                    // ロールを省略すれば閲覧者
                    json.assertThat().extractingPath("$.role").isEqualTo("VIEWER");
                });

        assertThat(users.findByGithubLoginIgnoreCase("new-member")).isPresent();
        assertThat(auditLogs.findAll())
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getAction()).isEqualTo("USER_ADDED");
                    assertThat(log.getActorLogin()).isEqualTo("admin-user");
                    assertThat(log.getAfterValue()).contains("new-member");
                });
    }

    @Test
    void 同じログイン名は二重に登録できない() {
        assertThat(mvc.post().uri("/api/v1/users").with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubLogin\":\"Viewer-User\"}"))
                .hasStatus(409)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("USER_ALREADY_EXISTS");
    }

    @Test
    void 不正なログイン名は入力エラー() {
        assertThat(mvc.post().uri("/api/v1/users").with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubLogin\":\"not a login\"}"))
                .hasStatus(400)
                .bodyJson().extractingPath("$.violations[0].field").isEqualTo("githubLogin");
    }

    @Test
    void 自分自身を降格できない() {
        assertThat(mvc.patch().uri("/api/v1/users/{id}", admin.getId())
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"VIEWER\"}"))
                .hasStatus(409)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("ADMIN_REQUIRED");
        assertThat(users.findById(admin.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.ADMIN);
    }

    @Test
    void ロールの変更は次のリクエストから効く() {
        UserAccount other = users.save(new UserAccount(Uuid7.generate(), "other-admin",
                UserRole.ADMIN, UserStatus.ACTIVE, admin.getId()));

        assertThat(mvc.patch().uri("/api/v1/users/{id}", other.getId())
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"VIEWER\"}"))
                .hasStatusOk();

        // セッションには ADMIN のまま残っていても、DB の現在値で判定される
        assertThat(mvc.get().uri("/api/v1/users").with(as("other-admin", "ADMIN")))
                .hasStatus(403);
        assertThat(auditLogs.findAll()).extracting(l -> l.getAction())
                .containsExactly("USER_ROLE_CHANGED");
    }

    @Test
    void 無効化された利用者のセッションは使えない() {
        assertThat(mvc.patch().uri("/api/v1/users/{id}",
                        users.findByGithubLoginIgnoreCase("viewer-user").orElseThrow().getId())
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"))
                .hasStatusOk();

        assertThat(mvc.get().uri("/api/v1/dashboard").with(as("viewer-user", "VIEWER")))
                .hasStatus(401);
    }

    @Test
    void CSRFトークンの無い更新は拒否される() {
        assertThat(mvc.post().uri("/api/v1/users").with(withoutCsrf("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubLogin\":\"someone\"}"))
                .hasStatus(403);
        assertThat(users.findByGithubLoginIgnoreCase("someone")).isEmpty();
    }

    @Test
    void 監査ログを新しい順に読める() throws Exception {
        for (String login : new String[] {"a-user", "b-user", "c-user"}) {
            assertThat(mvc.post().uri("/api/v1/users").with(as("admin-user", "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"githubLogin\":\"%s\"}".formatted(login)))
                    .hasStatus(201);
        }

        assertThat(mvc.get().uri("/api/v1/audit-logs?limit=2").with(as("admin-user", "ADMIN")))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.items.length()").isEqualTo(2);
                    json.assertThat().extractingPath("$.items[0].after.githubLogin")
                            .isEqualTo("c-user");
                    json.assertThat().extractingPath("$.hasMore").isEqualTo(true);
                });
        assertThat(mvc.get().uri("/api/v1/audit-logs").with(as("viewer-user", "VIEWER")))
                .hasStatus(403);

        // 画面のアクセシビリティ検査（M-10）で使う応答例（FixtureWriter）
        FixtureWriter.write("users.json", mvc.get().uri("/api/v1/users")
                .with(as("admin-user", "ADMIN")).exchange().getResponse().getContentAsString());
    }
}
