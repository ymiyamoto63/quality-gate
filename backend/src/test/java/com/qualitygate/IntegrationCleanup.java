package com.qualitygate;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.HttpCookie;
import java.util.List;

/**
 * 結合テストの共通の準備。
 *
 * <p>結合テストは Spring のコンテキスト（と PostgreSQL のコンテナ）を共有する。
 * テストごとに消すテーブルを列挙すると、テーブルを足したときに消し漏れが生じ、
 * 別のテストの外部キーに引っかかって順序依存の失敗になる。
 */
final class IntegrationCleanup {

    /** 結合テストの Ingest Token（application-test.yml の {@code quality-gate.ingest-tokens}）。 */
    static final String INGEST_TOKEN = "test-ingest-token-0123456789abcdef";
    /** 画面のログイン（application-test.yml の {@code quality-gate.login}）。 */
    static final String LOGIN_USERNAME = "quality";
    static final String LOGIN_PASSWORD = "test-login-password-0123";

    private IntegrationCleanup() {
    }

    static void deleteAll(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE findings, measurements, artifacts, runs, repositories CASCADE");
    }

    /**
     * 画面と同じ手順（CSRF の Cookie を受け取り、ヘッダで送り返してログイン）でログインし、
     * セッションと CSRF の Cookie を載せた {@code Cookie} ヘッダの値を返す。
     */
    static String login(RestClient client, String password) {
        ResponseEntity<Void> first = client.get().uri("/api/v1/me").retrieve().toBodilessEntity();
        String xsrf = cookie(first, "XSRF-TOKEN");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", LOGIN_USERNAME);
        form.add("password", password);
        ResponseEntity<Void> login = client.post().uri("/api/v1/login")
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + xsrf)
                .header("X-XSRF-TOKEN", xsrf)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().toBodilessEntity();
        if (login.getStatusCode() != HttpStatus.NO_CONTENT) {
            return null;
        }
        return "JSESSIONID=" + cookie(login, "JSESSIONID");
    }

    static String login(RestClient client) {
        return login(client, LOGIN_PASSWORD);
    }

    private static String cookie(ResponseEntity<?> response, String name) {
        List<String> headers = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        return headers.stream()
                .flatMap(header -> HttpCookie.parse(header).stream())
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
