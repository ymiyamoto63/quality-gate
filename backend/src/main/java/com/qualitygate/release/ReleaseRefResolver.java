package com.qualitygate.release;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.github.GitHubApiException;
import com.qualitygate.github.GitHubClient;
import com.qualitygate.github.GitHubProperties;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * リリース判定で指定されたタグ・コミットを、コミット SHA に解決する（UC-10）。
 *
 * <p>16 進数 7〜40 桁はコミット SHA とみなし、まず計測済みの Run から探す（GitHub API を使わない）。
 * それ以外はタグ名とみなし、GitHub API で解決する。ブランチ名は受け付けない。ブランチの先頭は
 * 時間とともに動くため、リリース判定の証跡にならない。
 */
@Component
public class ReleaseRefResolver {

    private static final Pattern SHA = Pattern.compile("^[0-9a-fA-F]{7,40}$");
    private static final Pattern FULL_SHA = Pattern.compile("^[0-9a-f]{40}$");
    /** git のタグ名に使えない文字（空白・制御文字・{@code ~^:?*[\}）と {@code ..} を拒否する。 */
    private static final Pattern INVALID_TAG = Pattern.compile("[\\s\\p{Cntrl}~^:?*\\[\\\\]|\\.\\.");
    private static final int MAX_LENGTH = 255;
    /** 注釈付きタグがタグを指す連鎖の上限。 */
    private static final int MAX_TAG_DEPTH = 3;

    private final RunRepository runs;
    private final GitHubClient github;
    private final GitHubProperties properties;

    public ReleaseRefResolver(RunRepository runs, GitHubClient github, GitHubProperties properties) {
        this.runs = runs;
        this.github = github;
        this.properties = properties;
    }

    /** 指定の種類。 */
    public enum RefType {
        TAG,
        COMMIT
    }

    /**
     * @param ref       利用者が指定した文字列（前後の空白を除いたもの）
     * @param commitSha 解決したコミット SHA。短い SHA を解決できなかった場合は指定のまま
     */
    public record Resolved(String ref, RefType type, String commitSha) {
    }

    public Resolved resolve(MonitoredRepository repository, String input) {
        String ref = input == null ? "" : input.strip();
        if (ref.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "タグかコミット SHA を指定してください");
        }
        if (ref.length() > MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "タグかコミット SHA は %d 文字以内で指定してください".formatted(MAX_LENGTH));
        }
        if (SHA.matcher(ref).matches()) {
            return resolveCommit(repository, ref.toLowerCase(Locale.ROOT));
        }
        if (INVALID_TAG.matcher(ref).find() || ref.startsWith("/") || ref.endsWith("/")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "タグ名として使えない文字を含んでいます: " + ref);
        }
        return new Resolved(ref, RefType.TAG, resolveTag(repository, ref));
    }

    private Resolved resolveCommit(MonitoredRepository repository, String sha) {
        List<String> measured = runs.findCommitShasLike(repository.getId(), sha + "%");
        if (measured.size() > 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "%s は複数のコミットに一致します。もっと長く指定してください".formatted(sha));
        }
        if (measured.size() == 1) {
            return new Resolved(sha, RefType.COMMIT, measured.getFirst());
        }
        if (FULL_SHA.matcher(sha).matches() || !properties.enabled()) {
            return new Resolved(sha, RefType.COMMIT, sha);
        }
        // 計測していない短い SHA。完全な SHA を示せるよう GitHub に聞くが、判定は「未計測」で変わらないため、
        // 聞けなくても止めない
        try {
            return new Resolved(sha, RefType.COMMIT, get(repository, "/commits/" + sha)
                    .map(commit -> commit.path("sha").asString(sha))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                            "コミット %s が %s に見つかりません".formatted(sha, repository.fullName()))));
        } catch (GitHubApiException e) {
            return new Resolved(sha, RefType.COMMIT, sha);
        }
    }

    private String resolveTag(MonitoredRepository repository, String tag) {
        if (!properties.enabled()) {
            throw new ApiException(ErrorCode.GITHUB_UNAVAILABLE,
                    "タグを解決するには GitHub API が必要です（QG_GITHUB_API_ENABLED）。コミット SHA で指定してください");
        }
        try {
            JsonNode object = get(repository, "/git/ref/tags/" + encodeRef(tag))
                    .map(ref -> ref.path("object"))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                            "タグ %s が %s に見つかりません".formatted(tag, repository.fullName())));
            // 注釈付きタグ（git tag -a）はタグオブジェクトを指すため、コミットまでたどる
            for (int depth = 0; "tag".equals(object.path("type").asString("")) && depth < MAX_TAG_DEPTH; depth++) {
                String tagSha = object.path("sha").asString("");
                object = get(repository, "/git/tags/" + tagSha)
                        .map(annotated -> annotated.path("object"))
                        .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                                "タグ %s の指す先が見つかりません".formatted(tag)));
            }
            String sha = object.path("sha").asString("");
            if (!"commit".equals(object.path("type").asString("")) || !FULL_SHA.matcher(sha).matches()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "タグ %s はコミットを指していません".formatted(tag));
            }
            return sha;
        } catch (GitHubApiException e) {
            throw new ApiException(ErrorCode.GITHUB_UNAVAILABLE,
                    "タグ %s をコミットに解決できませんでした。コミット SHA で指定してください（%s）"
                            .formatted(tag, e.getMessage()));
        }
    }

    private Optional<JsonNode> get(MonitoredRepository repository, String path) {
        return github.getRepositoryResource(repository.getOwner(), repository.getName(), path);
    }

    /** タグ名の {@code /} は区切りとして残し、それ以外を URL に使える形にする。 */
    private static String encodeRef(String ref) {
        return URLEncoder.encode(ref, StandardCharsets.UTF_8).replace("%2F", "/").replace("+", "%20");
    }
}
