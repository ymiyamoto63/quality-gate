package com.qualitygate.github;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GitHub の compare API を模したサーバに対して、移動・リネームの読み取りを確かめる。 */
class RenameResolverTest {

    private static final String BASE = "b".repeat(40);
    private static final String HEAD = "a".repeat(40);

    private HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void renamedのファイルだけを新しいパスから移動前のパスへ対応づける() {
        responses.put("/repos/o/r/compare/" + BASE + "..." + HEAD, """
                {"files": [
                  {"filename": "backend/src/main/java/a/New.java", "status": "renamed",
                   "previous_filename": "backend/src/main/java/a/Old.java"},
                  {"filename": "backend/src/main/java/a/Edited.java", "status": "modified"},
                  {"filename": "backend/src/main/java/a/Added.java", "status": "added"}
                ]}
                """);

        assertThat(resolver().renames("o", "r", BASE, HEAD))
                .containsExactly(Map.entry("backend/src/main/java/a/New.java", "backend/src/main/java/a/Old.java"));
    }

    @Test
    void 比較元のコミットが見つからなければ空() {
        assertThat(resolver().renames("o", "r", BASE, HEAD)).isEmpty();
    }

    @Test
    void GitHubのエラーは例外にする() {
        responses.put("/repos/o/r/compare/" + BASE + "..." + HEAD, "!500");

        assertThatThrownBy(() -> resolver().renames("o", "r", BASE, HEAD))
                .isInstanceOf(GitHubApiException.class);
    }

    private RenameResolver resolver() {
        GitHubProperties properties = new GitHubProperties(true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, null, null, Duration.ofSeconds(5));
        GitHubClient client = new GitHubClient(properties, JsonMapper.builder().build(),
                HttpClient.newHttpClient(), Clock.systemUTC());
        return new RenameResolver(properties, client, null);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = responses.get(exchange.getRequestURI().getRawPath());
        int status = body == null ? 404 : body.startsWith("!") ? Integer.parseInt(body.substring(1)) : 200;
        byte[] bytes = (body == null || status != 200 ? "{}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
