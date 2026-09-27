package com.qualitygate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 要求の誤りは 4xx で返す（docs/architecture.md 6.3）。
 *
 * <p>Spring MVC が投げる例外（未対応のメソッド、本文の形式、値の型など）を
 * 想定外の例外として 500 にすると、利用者の誤りがサーバの異常に見え、エラーのログにも紛れる。
 */
@SpringBootTest
@AbstractIntegrationTest
class ErrorResponseIT {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    private MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        mvc = MockMvcTester.create(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
    }

    @Test
    void 未対応のメソッドは405で使えるメソッドを示す() {
        MvcTestResult result = mvc.put().uri("/api/v1/release")
                .with(user(IntegrationCleanup.LOGIN_USERNAME)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .exchange();

        assertThat(result).hasStatus(405).hasHeader(HttpHeaders.ALLOW, "GET");
        assertThat(result).bodyJson().extractingPath("$.errorCode").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void 長すぎる指定は400() {
        assertThat(mvc.get().uri("/api/v1/release?ref={ref}", "v".repeat(256)).with(user(IntegrationCleanup.LOGIN_USERNAME)))
                .hasStatus(400)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 存在しないAPIは404() {
        assertThat(mvc.get().uri("/api/v1/nope").with(user(IntegrationCleanup.LOGIN_USERNAME)))
                .hasStatus(404)
                .bodyJson().extractingPath("$.errorCode").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void 応答できない形式を求められたら406() {
        assertThat(mvc.get().uri("/api/v1/release/history")
                .accept(MediaType.APPLICATION_XML)
                .with(user(IntegrationCleanup.LOGIN_USERNAME)))
                .hasStatus(406);
    }

    @Test
    void 未ログインのAPIは401() {
        assertThat(mvc.get().uri("/api/v1/release")).hasStatus(401);
        assertThat(mvc.get().uri("/api/v1/me")).hasStatus(401);
    }
}
