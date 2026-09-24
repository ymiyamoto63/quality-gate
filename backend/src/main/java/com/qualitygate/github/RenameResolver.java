package com.qualitygate.github;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 比較元のコミットから Run のコミットまでの、ファイルの移動・リネームを GitHub API で求める（指標仕様書 0.4）。
 *
 * <p>GitHub の compare API は git の rename 検出（類似度 50% 以上）で {@code status: renamed} と移動前のパスを返す。
 * compare API が返すファイルは 300 件までのため、それより大きな差分では一部の移動を取りこぼす。
 * 取りこぼした移動は、これまでどおり新規として扱われる（判定を止めない）。
 */
@Component
public class RenameResolver {

    private static final Logger log = LoggerFactory.getLogger(RenameResolver.class);

    private final GitHubProperties properties;
    private final GitHubClient client;
    private final MonitoredRepositoryRepository repositories;

    public RenameResolver(GitHubProperties properties, GitHubClient client,
                          MonitoredRepositoryRepository repositories) {
        this.properties = properties;
        this.client = client;
        this.repositories = repositories;
    }

    /**
     * @param fromCommits 比較元のコミット（比較元コミット、比較対象 Run のコミットなど）
     * @return 新しいパス → 移動前のパス。設定で無効にしている場合と、失敗した場合は空
     *         （空の対応表とは区別する。空の Optional なら次の再評価で求め直す）
     */
    public Optional<Map<String, String>> resolveFor(Run run, Collection<String> fromCommits) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        Optional<MonitoredRepository> repository = repositories.findById(run.getRepositoryId());
        if (repository.isEmpty()) {
            return Optional.empty();
        }
        MonitoredRepository target = repository.get();
        try {
            Map<String, String> renames = new LinkedHashMap<>();
            for (String from : fromCommits) {
                renames(target.getOwner(), target.getName(), from, run.getCommitSha())
                        .forEach(renames::putIfAbsent);
            }
            log.info("ファイルの移動を GitHub API で求めました runId={} 件数={}", run.getId(), renames.size());
            return Optional.of(Map.copyOf(renames));
        } catch (RuntimeException e) {
            log.warn("ファイルの移動を GitHub API で求められませんでした。移動は追跡せずに判定します runId={} 理由={}",
                    run.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    Map<String, String> renames(String owner, String name, String from, String to) {
        Map<String, String> renames = new LinkedHashMap<>();
        client.getRepositoryResource(owner, name, "/compare/%s...%s".formatted(from, to))
                .ifPresent(comparison -> comparison.path("files").forEach(file -> {
                    if ("renamed".equals(file.path("status").asString(""))) {
                        String previous = file.path("previous_filename").asString("");
                        String current = file.path("filename").asString("");
                        if (!previous.isBlank() && !current.isBlank()) {
                            renames.put(current, previous);
                        }
                    }
                }));
        return renames;
    }
}
