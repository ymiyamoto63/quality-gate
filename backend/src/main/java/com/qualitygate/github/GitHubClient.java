package com.qualitygate.github;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GitHub の REST API を読み取りだけで呼ぶ。
 *
 * <p>外部 API の障害の影響範囲を閉じ込めるため、このパッケージは取り込み（ingest）や判定（evaluate）からは
 * 呼ばない（ModuleDependencyTest）。呼び出し側のジョブが失敗を握りつぶせるよう、失敗は
 * {@link GitHubApiException} にまとめる。
 */
@Component
public class GitHubClient {

    private static final String API_VERSION = "2022-11-28";

    /** インストールトークンは 1 時間有効。期限の 5 分前に取り直す。 */
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5);

    private final GitHubProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final Clock clock;
    private final GitHubAppJwt appJwt;
    private final Map<String, CachedToken> installationTokens = new ConcurrentHashMap<>();

    @Autowired
    public GitHubClient(GitHubProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), Clock.systemUTC());
    }

    GitHubClient(GitHubProperties properties, ObjectMapper objectMapper, HttpClient http, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = http;
        this.clock = clock;
        this.appJwt = properties.usesApp() ? new GitHubAppJwt(properties.appId(), properties.privateKey()) : null;
    }

    /**
     * {@code /repos/<owner>/<name><path>} を GET する。404 は空（リポジトリ・コミット・PR が無い、または見えない）。
     *
     * @param path {@code /compare/main...abc} のように {@code /} で始まるパス
     */
    public Optional<JsonNode> getRepositoryResource(String owner, String name, String path) {
        String repositoryPath = "/repos/%s/%s".formatted(encode(owner), encode(name));
        HttpRequest.Builder request = request(repositoryPath + path).GET();
        authorization(owner, name).ifPresent(value -> request.header("Authorization", value));
        return send(request.build(), owner + "/" + name + path);
    }

    private Optional<String> authorization(String owner, String name) {
        if (appJwt != null) {
            return Optional.of("Bearer " + installationToken(owner, name));
        }
        if (properties.usesToken()) {
            return Optional.of("Bearer " + properties.token().strip());
        }
        return Optional.empty();
    }

    /** 対象リポジトリにインストールされた App のトークン。リポジトリごとにキャッシュする。 */
    private String installationToken(String owner, String name) {
        String key = owner + "/" + name;
        CachedToken cached = installationTokens.get(key);
        Instant now = clock.instant();
        if (cached != null && cached.expiresAt().minus(TOKEN_MARGIN).isAfter(now)) {
            return cached.token();
        }
        String jwt = "Bearer " + appJwt.create(now);
        JsonNode installation = send(request("/repos/%s/%s/installation".formatted(encode(owner), encode(name)))
                .header("Authorization", jwt).GET().build(), key + " のインストール")
                .orElseThrow(() -> new GitHubApiException(
                        "GitHub App が %s にインストールされていません".formatted(key)));
        long installationId = installation.path("id").asLong();

        // 権限は対象リポジトリの読み取りだけに絞って発行する
        String body = "{\"repositories\":[\"%s\"],\"permissions\":{\"contents\":\"read\",\"pull_requests\":\"read\"}}"
                .formatted(name.replace("\"", ""));
        JsonNode token = send(request("/app/installations/%d/access_tokens".formatted(installationId))
                .header("Authorization", jwt)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), key + " のトークン")
                .orElseThrow(() -> new GitHubApiException("インストールトークンを発行できませんでした: " + key));
        CachedToken fresh = new CachedToken(token.path("token").asString(),
                Instant.parse(token.path("expires_at").asString(now.plus(Duration.ofHours(1)).toString())));
        installationTokens.put(key, fresh);
        return fresh.token();
    }

    private HttpRequest.Builder request(String path) {
        String base = properties.apiUrl().toString().replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(properties.timeout())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "quality-gate");
    }

    private Optional<JsonNode> send(HttpRequest request, String what) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GitHubApiException("GitHub API に接続できませんでした（%s）: %s".formatted(what, e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException("GitHub API の呼び出しが中断されました（%s）".formatted(what), e);
        }
        int status = response.statusCode();
        if (status == 404) {
            return Optional.empty();
        }
        if (status < 200 || status >= 300) {
            throw new GitHubApiException("GitHub API がエラーを返しました（%s、HTTP %d）".formatted(what, status));
        }
        return Optional.of(objectMapper.readTree(response.body()));
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
