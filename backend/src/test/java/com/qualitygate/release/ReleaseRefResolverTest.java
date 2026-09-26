package com.qualitygate.release;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.github.GitHubClient;
import com.qualitygate.github.GitHubProperties;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** GitHub API を模したサーバに対して、タグ・コミットの解決を確かめる。 */
class ReleaseRefResolverTest {

    private static final String COMMIT = "a".repeat(40);
    private static final String TAG_OBJECT = "b".repeat(40);

    private HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final RunRepository runs = mock(RunRepository.class);
    private final MonitoredRepository repository = new MonitoredRepository(UUID.randomUUID(), "o", "r", null);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        when(runs.findCommitShasLike(any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 軽量タグはそのままコミットに解決する() {
        responses.put("/repos/o/r/git/ref/tags/v1.2.0",
                "{\"object\": {\"type\": \"commit\", \"sha\": \"" + COMMIT + "\"}}");

        var resolved = resolver(true).resolve(repository, " v1.2.0 ");

        assertThat(resolved.ref()).isEqualTo("v1.2.0");
        assertThat(resolved.type()).isEqualTo(ReleaseRefResolver.RefType.TAG);
        assertThat(resolved.commitSha()).isEqualTo(COMMIT);
    }

    @Test
    void 注釈付きタグはタグオブジェクトをたどる() {
        responses.put("/repos/o/r/git/ref/tags/release/2026-09",
                "{\"object\": {\"type\": \"tag\", \"sha\": \"" + TAG_OBJECT + "\"}}");
        responses.put("/repos/o/r/git/tags/" + TAG_OBJECT,
                "{\"object\": {\"type\": \"commit\", \"sha\": \"" + COMMIT + "\"}}");

        assertThat(resolver(true).resolve(repository, "release/2026-09").commitSha()).isEqualTo(COMMIT);
        assertThat(requests).containsExactly("/repos/o/r/git/ref/tags/release/2026-09",
                "/repos/o/r/git/tags/" + TAG_OBJECT);
    }

    @Test
    void 無いタグは404で返す() {
        assertThatThrownBy(() -> resolver(true).resolve(repository, "v9.9.9"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND))
                .hasMessageContaining("タグ v9.9.9 が o/r に見つかりません");
    }

    @Test
    void GitHubAPIを使えなければタグはSHAでの指定を案内する() {
        assertThatThrownBy(() -> resolver(false).resolve(repository, "v1.2.0"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(ErrorCode.GITHUB_UNAVAILABLE))
                .hasMessageContaining("コミット SHA で指定してください");
    }

    @Test
    void 短いSHAは計測済みのRunから完全なSHAにする() {
        when(runs.findCommitShasLike(any(), eq("aaaaaaa%"))).thenReturn(List.of(COMMIT));

        var resolved = resolver(false).resolve(repository, "AAAAAAA");

        assertThat(resolved.type()).isEqualTo(ReleaseRefResolver.RefType.COMMIT);
        assertThat(resolved.commitSha()).isEqualTo(COMMIT);
        assertThat(requests).isEmpty();
    }

    @Test
    void 複数のコミットに一致する短いSHAは拒否する() {
        when(runs.findCommitShasLike(any(), eq("aaaaaaa%"))).thenReturn(List.of(COMMIT, "aaaaaaab" + "c".repeat(32)));

        assertThatThrownBy(() -> resolver(false).resolve(repository, "aaaaaaa"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("複数のコミットに一致します");
    }

    @Test
    void 計測していない短いSHAはGitHubで完全なSHAにする() {
        responses.put("/repos/o/r/commits/aaaaaaa", "{\"sha\": \"" + COMMIT + "\"}");

        assertThat(resolver(true).resolve(repository, "aaaaaaa").commitSha()).isEqualTo(COMMIT);
    }

    @Test
    void タグ名に使えない文字は拒否する() {
        for (String ref : List.of("", "  ", "v1..2", "a b", "x:y", "/v1", "v1/")) {
            assertThatThrownBy(() -> resolver(true).resolve(repository, ref))
                    .as(ref)
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).errorCode())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(requests).isEmpty();
    }

    private ReleaseRefResolver resolver(boolean enabled) {
        GitHubProperties properties = new GitHubProperties(enabled,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, null, null,
                Duration.ofSeconds(5));
        GitHubClient client = new GitHubClient(properties, JsonMapper.builder().build());
        return new ReleaseRefResolver(runs, client, properties);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        requests.add(path);
        String body = responses.get(path);
        byte[] bytes = (body == null ? "{\"message\": \"Not Found\"}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
