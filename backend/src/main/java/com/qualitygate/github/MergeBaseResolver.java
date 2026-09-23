package com.qualitygate.github;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code baseCommitSha} が省略された Run の比較元を GitHub API で求める（FR-05-4）。
 *
 * <p>比較元の決め方は収集ランナー（collector/bin/fetch.sh）と同じにする。
 * <ul>
 *   <li>PR: マージ先のブランチとの merge-base</li>
 *   <li>既定ブランチ: 直前のコミット（最初の親）</li>
 *   <li>それ以外のブランチ: 既定ブランチとの merge-base</li>
 * </ul>
 * 求められなければ空を返し、Run は比較元なしのまま判定する（取り込みや判定を GitHub の障害で止めない）。
 */
@Component
public class MergeBaseResolver {

    private static final Logger log = LoggerFactory.getLogger(MergeBaseResolver.class);

    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");

    private final GitHubProperties properties;
    private final GitHubClient client;
    private final MonitoredRepositoryRepository repositories;

    public MergeBaseResolver(GitHubProperties properties, GitHubClient client,
                             MonitoredRepositoryRepository repositories) {
        this.properties = properties;
        this.client = client;
        this.repositories = repositories;
    }

    /** 比較元が省略された Run について求める。設定で無効にしている場合と、失敗した場合は空。 */
    public Optional<String> resolveFor(Run run) {
        if (!properties.enabled() || run.getBaseCommitSha() != null) {
            return Optional.empty();
        }
        Optional<MonitoredRepository> repository = repositories.findById(run.getRepositoryId());
        if (repository.isEmpty()) {
            return Optional.empty();
        }
        MonitoredRepository target = repository.get();
        try {
            Optional<String> base = resolve(target.getOwner(), target.getName(), target.getDefaultBranch(),
                    run.getCommitSha(), run.getBranch(), run.getPullRequestNumber());
            base.ifPresentOrElse(
                    sha -> log.info("比較元を GitHub API で求めました runId={} base={}", run.getId(), sha),
                    () -> log.info("比較元を求められませんでした（コミットが見つからない、または最初のコミット） runId={}",
                            run.getId()));
            return base;
        } catch (RuntimeException e) {
            log.warn("比較元を GitHub API で求められませんでした。比較元なしで判定します runId={} 理由={}",
                    run.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    Optional<String> resolve(String owner, String name, String defaultBranch, String commitSha,
                             String branch, Integer pullRequestNumber) {
        String baseBranch = defaultBranch;
        if (pullRequestNumber != null) {
            baseBranch = client.getRepositoryResource(owner, name, "/pulls/" + pullRequestNumber)
                    .map(pr -> pr.path("base").path("ref").asString(""))
                    .filter(ref -> !ref.isBlank())
                    .orElse(defaultBranch);
        } else if (branch != null && branch.equals(defaultBranch)) {
            return client.getRepositoryResource(owner, name, "/commits/" + commitSha)
                    .map(commit -> commit.path("parents").path(0).path("sha").asString(""))
                    .filter(sha -> SHA.matcher(sha).matches());
        }
        return client.getRepositoryResource(owner, name,
                        "/compare/%s...%s".formatted(encodeRef(baseBranch), commitSha))
                .map(MergeBaseResolver::mergeBaseOf)
                .filter(sha -> SHA.matcher(sha).matches());
    }

    private static String mergeBaseOf(JsonNode comparison) {
        return comparison.path("merge_base_commit").path("sha").asString("");
    }

    /** ブランチ名の {@code /} は区切りとして残し、それ以外を URL に使える形にする。 */
    private static String encodeRef(String ref) {
        return URLEncoder.encode(ref, StandardCharsets.UTF_8).replace("%2F", "/");
    }
}
