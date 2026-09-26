package com.qualitygate.query;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.query.dto.RepositoryResponses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** リポジトリの一覧と詳細（S-02）。判定はせず、保存済みの判定済み Run を引くだけ。 */
@Service
public class RepositoryQueryService {

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final GateConfigRepository configs;

    public RepositoryQueryService(MonitoredRepositoryRepository repositories,
                                  RunRepository runs, GateConfigRepository configs) {
        this.repositories = repositories;
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
        Optional<Run> latest = runs.findFirstByRepositoryIdAndStatusOrderByMeasuredAtDescAttemptDesc(
                repositoryId, RunStatus.EVALUATED);
        Optional<Run> lastFull = runs.findFirstByRepositoryIdAndStatusAndCompletenessOrderByMeasuredAtDescAttemptDesc(
                repositoryId, RunStatus.EVALUATED, Completeness.FULL);

        return new RepositoryResponses.RepositoryDetail(
                itemOf(repository),
                latest.map(RepositoryQueryService::latestOf).orElse(null),
                lastFull.map(Run::getId).orElse(null),
                new RepositoryResponses.RepositoryFreshness(latest.map(Run::getMeasuredAt).orElse(null),
                        lastFull.map(Run::getMeasuredAt).orElse(null)),
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
