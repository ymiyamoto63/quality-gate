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
        authorization(owner, name, READ).ifPresent(value -> request.header("Authorization", value));
        return send(request.build(), owner + "/" + name + path);
    }

    /**
     * {@code /repos/<owner>/<name><path>} に JSON を POST する。GitHub App での認証が必要（Check Run の作成など）。
     *
     * @param permissions インストールトークンに付ける権限（例: {@code "checks": "write"}）。必要なものだけに絞る
     */
    public JsonNode postRepositoryResource(String owner, String name, String path, Object body,
                                           Map<String, String> permissions) {
        if (appJwt == null) {
            throw new GitHubApiException("この操作には GitHub App（QG_GITHUB_APP_ID / QG_GITHUB_APP_PRIVATE_KEY）が必要です");
        }
        String repositoryPath = "/repos/%s/%s".formatted(encode(owner), encode(name));
        HttpRequest request = request(repositoryPath + path)
                .header("Authorization", "Bearer " + installationToken(owner, name, permissions))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return send(request, owner + "/" + name + path)
                .orElseThrow(() -> new GitHubApiException("GitHub API が 404 を返しました（%s/%s%s）"
                        .formatted(owner, name, path)));
    }

    /** GitHub App を使うか（Check Run のように App でしか呼べない API の可否）。 */
    public boolean usesApp() {
        return appJwt != null;
    }

    private static final Map<String, String> READ = Map.of("contents", "read", "pull_requests", "read");

    private Optional<String> authorization(String owner, String name, Map<String, String> permissions) {
        if (appJwt != null) {
            return Optional.of("Bearer " + installationToken(owner, name, permissions));
        }
        if (properties.usesToken()) {
            return Optional.of("Bearer " + properties.token().strip());
        }
        return Optional.empty();
    }

    /** 対象リポジトリにインストールされた App のトークン。リポジトリと権限の組ごとにキャッシュする。 */
    private String installationToken(String owner, String name, Map<String, String> permissions) {
        String key = owner + "/" + name + " " + new java.util.TreeMap<>(permissions);
        CachedToken cached = installationTokens.get(key);
        Instant now = clock.instant();
        if (cached != null && cached.expiresAt().minus(TOKEN_MARGIN).isAfter(now)) {
            return cached.token();
        }
        String jwt = "Bearer " + appJwt.create(now);
        JsonNode installation = send(request("/repos/%s/%s/installation".formatted(encode(owner), encode(name)))
                .header("Authorization", jwt).GET().build(), owner + "/" + name + " のインストール")
                .orElseThrow(() -> new GitHubApiException(
                        "GitHub App が %s/%s にインストールされていません".formatted(owner, name)));
        long installationId = installation.path("id").asLong();

        // 対象リポジトリだけ・必要な権限だけに絞って発行する
        String body = objectMapper.writeValueAsString(Map.of("repositories", java.util.List.of(name),
                "permissions", permissions));
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
