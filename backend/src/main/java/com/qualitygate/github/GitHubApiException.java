package com.qualitygate.github;

/** GitHub API の呼び出しの失敗（接続できない、エラー応答、App が未インストールなど）。 */
public class GitHubApiException extends RuntimeException {

    public GitHubApiException(String message) {
        super(message);
    }

    public GitHubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
