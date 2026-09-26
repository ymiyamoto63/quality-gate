package com.qualitygate;

import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
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

import static com.qualitygate.TestSessions.as;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 要求の誤りは 4xx で返す（07-api-design.md 7 章）。
 *
 * <p>Spring MVC が投げる例外（未対応のメソッド、本文の形式、値の型、必須パラメータの欠落など）を
 * 想定外の例外として 500 にすると、利用者の誤りがサーバの異常に見え、エラーのログにも紛れる。
 */
@SpringBootTest
@AbstractIntegrationTest
class ErrorResponseIT {

    @Autowired WebApplicationContext context;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;

    private MockMvcTester mvc;
    private final String repositoryId = Uuid7.generate().toString();

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        users.save(new UserAccount(Uuid7.generate(), "admin-user", UserRole.ADMIN, UserStatus.ACTIVE, null));
        mvc = TestSessions.tester(context);
    }

    @Test
    void 未対応のメソッドは405で使えるメソッドを示す() {
        MvcTestResult result = mvc.put().uri("/api/v1/repositories/{id}/config", repositoryId)
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .exchange();

        assertThat(result).hasStatus(405).hasHeader(HttpHeaders.ALLOW, "GET");
        assertThat(result).bodyJson().extractingPath("$.errorCode").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void 未対応の本文の形式は415() {
        assertThat(mvc.post().uri("/api/v1/repositories")
                .with(as("admin-user", "ADMIN"))
                .contentType(MediaType.TEXT_PLAIN).content("acme/web-app"))
                .hasStatus(415)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void 形式の合わない値は400() {
        assertThat(mvc.get().uri("/api/v1/repositories/not-a-uuid").with(as("admin-user", "ADMIN")))
                .hasStatus(400)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("VALIDATION_FAILED");
        assertThat(mvc.get().uri("/api/v1/runs?limit=abc").with(as("admin-user", "ADMIN")))
                .hasStatus(400);
    }

    @Test
    void 必須パラメータが無ければ400() {
        assertThat(mvc.get().uri("/api/v1/repositories/{id}/trends", repositoryId)
                .with(as("admin-user", "ADMIN")))
                .hasStatus(400)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 存在しないAPIは404() {
        assertThat(mvc.get().uri("/api/v1/nope").with(as("admin-user", "ADMIN")))
                .hasStatus(404)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void 応答できない形式を求められたら406() {
        assertThat(mvc.get().uri("/api/v1/repositories")
                .accept(MediaType.APPLICATION_XML)
                .with(as("admin-user", "ADMIN")))
                .hasStatus(406);
    }
}
