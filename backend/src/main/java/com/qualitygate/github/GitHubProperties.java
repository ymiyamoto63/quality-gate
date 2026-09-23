package com.qualitygate.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * GitHub API の呼び出し設定（{@code quality-gate.github.*}）。
 *
 * <p>認証は GitHub App（{@code appId} と {@code privateKey}。対象リポジトリにインストールした App の
 * インストールトークンを使う）か、トークン（{@code token}）のどちらか。両方あれば App を使う。
 * どちらも無ければ認証なしで呼ぶ（public リポジトリだけ。1 時間 60 回まで）。
 *
 * @param enabled    GitHub API を呼ぶか。false なら merge-base を解決しない
 * @param apiUrl     API の URL（GitHub Enterprise Server なら {@code https://<host>/api/v3}）
 * @param appId      GitHub App の App ID
 * @param privateKey GitHub App の秘密鍵（PEM。PKCS#1 / PKCS#8 のどちらでもよい）
 * @param token      App を使わない場合のトークン（Contents: Read-only と Pull requests: Read-only）
 * @param timeout    1 回の呼び出しのタイムアウト
 */
@ConfigurationProperties(prefix = "quality-gate.github")
public record GitHubProperties(
        boolean enabled,
        URI apiUrl,
        String appId,
        String privateKey,
        String token,
        Duration timeout) {

    public GitHubProperties {
        if (apiUrl == null) {
            apiUrl = URI.create("https://api.github.com");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(10);
        }
    }

    /** GitHub App で認証するか。 */
    public boolean usesApp() {
        return appId != null && !appId.isBlank() && privateKey != null && !privateKey.isBlank();
    }

    /** トークンで認証するか。 */
    public boolean usesToken() {
        return !usesApp() && token != null && !token.isBlank();
    }
}
