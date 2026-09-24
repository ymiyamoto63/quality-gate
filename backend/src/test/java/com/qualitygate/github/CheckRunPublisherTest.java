package com.qualitygate.github;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.RunnerType;
import com.qualitygate.domain.model.Verdict;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Check Run の中身と、GitHub App のトークンで Checks に書く流れ（GitHub API を模したサーバで確かめる）。 */
class CheckRunPublisherTest {

    private static final String SHA = "a".repeat(40);

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> authorizations = new ConcurrentHashMap<>();
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath();
            bodies.put(key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.put(key, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String response = switch (key) {
                case "GET /repos/o/r/installation" -> "{\"id\": 7}";
                case "POST /app/installations/7/access_tokens" ->
                        "{\"token\": \"ghs_checks\", \"expires_at\": \"2026-09-24T01:00:00Z\"}";
                case "POST /repos/o/r/check-runs" -> "{\"id\": 4242}";
                default -> null;
            };
            byte[] bytes = (response == null ? "{}" : response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response == null ? 404 : key.endsWith("check-runs") ? 201 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void checkRunでは合否に関わらずneutralにしblockingでは合否を反映する() {
        assertThat(CheckRunPublisher.conclusionOf(Verdict.FAIL, "check-run")).isEqualTo("neutral");
        assertThat(CheckRunPublisher.conclusionOf(Verdict.PASS, "check-run")).isEqualTo("neutral");
        assertThat(CheckRunPublisher.conclusionOf(Verdict.FAIL, "blocking")).isEqualTo("failure");
        assertThat(CheckRunPublisher.conclusionOf(Verdict.PASS_WITH_WARNINGS, "blocking")).isEqualTo("success");
    }

    @Test
    void AppのトークンにChecksの書き込み権限だけを付けてCheckRunを作る() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        GitHubProperties properties = new GitHubProperties(true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "1234", pkcs1Pem(keys), null,
                Duration.ofSeconds(5));
        CheckRunPublisher publisher = new CheckRunPublisher(new GitHubClient(properties, json,
                HttpClient.newHttpClient(), Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC)));

        long id = publisher.publish(repository(), run(Verdict.FAIL), List.of(), "check-run",
                "https://qg.example/runs/1");

        assertThat(id).isEqualTo(4242);
        JsonNode tokenRequest = json.readTree(bodies.get("POST /app/installations/7/access_tokens"));
        assertThat(tokenRequest.path("permissions").path("checks").asString()).isEqualTo("write");
        assertThat(tokenRequest.path("permissions").size()).isEqualTo(1);
        assertThat(tokenRequest.path("repositories").get(0).asString()).isEqualTo("r");
        assertThat(authorizations.get("POST /repos/o/r/check-runs")).isEqualTo("Bearer ghs_checks");

        JsonNode checkRun = json.readTree(bodies.get("POST /repos/o/r/check-runs"));
        assertThat(checkRun.path("name").asString()).isEqualTo("quality-gate");
        assertThat(checkRun.path("head_sha").asString()).isEqualTo(SHA);
        assertThat(checkRun.path("conclusion").asString()).isEqualTo("neutral");
        assertThat(checkRun.path("details_url").asString()).isEqualTo("https://qg.example/runs/1");
        assertThat(checkRun.path("output").path("title").asString()).isEqualTo("不合格");
        assertThat(checkRun.path("output").path("summary").asString()).contains("neutral で報告");
    }

    @Test
    void Appが無ければ作らない() {
        GitHubProperties properties = new GitHubProperties(true, URI.create("http://127.0.0.1:1"), null, null,
                "ghp_token", Duration.ofSeconds(1));
        CheckRunPublisher publisher = new CheckRunPublisher(new GitHubClient(properties, json));

        assertThat(publisher.available()).isFalse();
        assertThatThrownBy(() -> publisher.publish(repository(), run(Verdict.PASS), List.of(), "check-run", "x"))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("GitHub App");
    }

    private static MonitoredRepository repository() {
        return new MonitoredRepository(UUID.randomUUID(), "o", "r", UUID.randomUUID());
    }

    private static Run run(Verdict verdict) {
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), SHA, "main", RunnerType.SELF_HOSTED, "it",
                Instant.parse("2026-09-24T00:00:00Z"), 1);
        run.markEvaluated(verdict, Completeness.FULL, Instant.parse("2026-09-24T00:01:00Z"));
        return run;
    }

    private static String pkcs1Pem(KeyPair keys) {
        byte[] pkcs8 = keys.getPrivate().getEncoded();
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
        return "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs1)
                + "\n-----END RSA PRIVATE KEY-----\n";
    }
}
