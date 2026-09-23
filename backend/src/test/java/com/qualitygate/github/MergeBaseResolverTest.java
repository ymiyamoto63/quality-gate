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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GitHub API を模したサーバに対して、比較元の決め方と認証を確かめる。 */
class MergeBaseResolverTest {

    private static final String HEAD = "a".repeat(40);
    private static final String PARENT = "b".repeat(40);
    private static final String MERGE_BASE = "c".repeat(40);

    private HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        responses.put("GET /repos/o/r/pulls/5", "{\"base\": {\"ref\": \"release/2026\"}}");
        responses.put("GET /repos/o/r/compare/release/2026..." + HEAD,
                "{\"merge_base_commit\": {\"sha\": \"" + MERGE_BASE + "\"}}");
        responses.put("GET /repos/o/r/compare/main..." + HEAD,
                "{\"merge_base_commit\": {\"sha\": \"" + PARENT + "\"}}");
        responses.put("GET /repos/o/r/commits/" + HEAD, "{\"parents\": [{\"sha\": \"" + PARENT + "\"}]}");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void PRはマージ先のブランチとのmergeBaseを使う() {
        assertThat(resolver(properties(null, null, "ghp_test")).resolve("o", "r", "main", HEAD, "feature/x", 5))
                .contains(MERGE_BASE);
        assertThat(authorizations).containsOnly("Bearer ghp_test");
    }

    @Test
    void 既定ブランチは直前のコミットを使う() {
        assertThat(resolver(properties(null, null, null)).resolve("o", "r", "main", HEAD, "main", null))
                .contains(PARENT);
        assertThat(requests).containsExactly("GET /repos/o/r/commits/" + HEAD);
        assertThat(authorizations).containsOnly("");
    }

    @Test
    void それ以外のブランチは既定ブランチとのmergeBaseを使う() {
        assertThat(resolver(properties(null, null, null)).resolve("o", "r", "main", HEAD, "feature/x", null))
                .contains(PARENT);
    }

    @Test
    void 見つからなければ空にする() {
        assertThat(resolver(properties(null, null, null)).resolve("o", "r", "main", "d".repeat(40), "topic", null))
                .isEmpty();
    }

    @Test
    void エラー応答は例外にする() {
        responses.put("GET /repos/o/r/compare/main..." + HEAD, "!500");

        assertThatThrownBy(() -> resolver(properties(null, null, null))
                .resolve("o", "r", "main", HEAD, "topic", null))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void GitHubAppのインストールトークンで呼びキャッシュする() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        responses.put("GET /repos/o/r/installation", "{\"id\": 42}");
        responses.put("POST /app/installations/42/access_tokens",
                "{\"token\": \"ghs_installation\", \"expires_at\": \"2026-09-24T01:00:00Z\"}");
        MergeBaseResolver resolver = resolver(properties("1234", pkcs1Pem(keys), null));

        resolver.resolve("o", "r", "main", HEAD, "main", null);
        resolver.resolve("o", "r", "main", HEAD, "topic", null);

        assertThat(requests).containsExactly(
                "GET /repos/o/r/installation",
                "POST /app/installations/42/access_tokens",
                "GET /repos/o/r/commits/" + HEAD,
                "GET /repos/o/r/compare/main..." + HEAD);
        assertThat(authorizations.get(0)).startsWith("Bearer ey");
        assertThat(authorizations.subList(2, 4)).containsOnly("Bearer ghs_installation");
    }

    @Test
    void JWTはRS256で署名しAppIDを発行者にする() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Instant now = Instant.parse("2026-09-24T00:00:00Z");

        String jwt = new GitHubAppJwt("1234", pkcs1Pem(keys)).create(now);

        String[] parts = jwt.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"iss\":\"1234\"")
                .contains("\"iat\":" + (now.getEpochSecond() - 60))
                .contains("\"exp\":" + (now.getEpochSecond() + 540));
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void PKCS8の秘密鍵も読める() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        assertThat(GitHubAppJwt.parse(pem).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
        assertThat(GitHubAppJwt.parse(pkcs1Pem(keys)).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
    }

    private MergeBaseResolver resolver(GitHubProperties properties) {
        GitHubClient client = new GitHubClient(properties, JsonMapper.builder().build(),
                HttpClient.newHttpClient(), Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC));
        return new MergeBaseResolver(properties, client, null);
    }

    private GitHubProperties properties(String appId, String privateKey, String token) {
        return new GitHubProperties(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                appId, privateKey, token, Duration.ofSeconds(5));
    }

    /** GitHub がダウンロードさせる形（PKCS#1）。PKCS#8 の中身の OCTET STRING を取り出す。 */
    private static String pkcs1Pem(KeyPair keys) {
        byte[] pkcs8 = keys.getPrivate().getEncoded();
        // SEQUENCE(4) + version(3) + AlgorithmIdentifier(15) + OCTET STRING のヘッダ(4)
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
        return "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs1)
                + "\n-----END RSA PRIVATE KEY-----\n";
    }

    private void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath();
        requests.add(key);
        authorizations.add(Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization")).orElse(""));
        String body = responses.get(key);
        int status = body == null ? 404 : body.startsWith("!") ? Integer.parseInt(body.substring(1)) : 200;
        byte[] bytes = (body == null || status != 200 ? "{}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
