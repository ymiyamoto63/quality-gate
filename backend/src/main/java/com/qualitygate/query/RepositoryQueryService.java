package com.qualitygate.query;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.RepositorySummary;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.query.dto.RepositoryResponses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** リポジトリの一覧と詳細（S-02）。判定はせず、読み取りモデルを組み替えるだけ。 */
@Service
public class RepositoryQueryService {

    private final MonitoredRepositoryRepository repositories;
    private final RepositorySummaryRepository summaries;
    private final RunRepository runs;
    private final GateConfigRepository configs;

    public RepositoryQueryService(MonitoredRepositoryRepository repositories,
                                  RepositorySummaryRepository summaries, RunRepository runs,
                                  GateConfigRepository configs) {
        this.repositories = repositories;
        this.summaries = summaries;
        this.runs = runs;
        this.configs = configs;
    }

    @Transactional(readOnly = true)
    public RepositoryResponses.RepositoryList list() {
        return new RepositoryResponses.RepositoryList(repositories.findAllByOrderByOwnerAscNameAsc()
                .stream().map(RepositoryQueryService::itemOf).toList());
    }

    @Transactional(readOnly = true)
    public RepositoryResponses.RepositoryDetail detail(UUID repositoryId) {
        MonitoredRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> ApiException.notFound("リポジトリ", repositoryId));
        Optional<RepositorySummary> summary = summaries.findById(repositoryId);

        RepositoryResponses.LatestRunSummary latest = summary
                .map(RepositorySummary::getLatestRunId)
                .flatMap(runs::findById)
                .map(RepositoryQueryService::latestOf)
                .orElse(null);

        Instant lastMeasured = summary.map(RepositorySummary::getLatestMeasuredAt).orElse(null);
        Instant lastFull = summary.map(RepositorySummary::getLastFullMeasuredAt).orElse(null);

        return new RepositoryResponses.RepositoryDetail(
                itemOf(repository),
                latest,
                summary.map(RepositorySummary::getLastFullRunId).orElse(null),
                new RepositoryResponses.RepositoryFreshness(lastMeasured, lastFull),
                configs.findFirstByRepositoryIdOrderByVersionDesc(repositoryId)
                        .map(c -> c.getVersion()).orElse(null));
    }

    static RepositoryResponses.RepositoryItem itemOf(MonitoredRepository repository) {
        return new RepositoryResponses.RepositoryItem(repository.getId(), repository.fullName(),
                repository.getOwner(), repository.getName(), repository.getDefaultBranch(),
                repository.isEnabled(),
                repository.getCreatedAt());
    }

    private static RepositoryResponses.LatestRunSummary latestOf(Run run) {
        return new RepositoryResponses.LatestRunSummary(run.getId(), run.getCommitSha(),
                run.getBranch(), run.getStatus(), run.getVerdict(), run.getCompleteness(),
                run.getMeasuredAt());
    }
}
