package com.qualitygate.query;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.RepositoryComponent;
import com.qualitygate.domain.entity.RepositorySummary;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositoryComponentRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.query.dto.RepositoryResponses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** リポジトリの一覧と詳細（S-02）。判定はせず、読み取りモデルを組み替えるだけ。 */
@Service
public class RepositoryQueryService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final MonitoredRepositoryRepository repositories;
    private final RepositoryComponentRepository components;
    private final RepositorySummaryRepository summaries;
    private final RunRepository runs;
    private final GateConfigRepository configs;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("java:S107")
    public RepositoryQueryService(MonitoredRepositoryRepository repositories,
                                  RepositoryComponentRepository components,
                                  RepositorySummaryRepository summaries, RunRepository runs,
                                  GateConfigRepository configs, ObjectMapper objectMapper) {
        this.repositories = repositories;
        this.components = components;
        this.summaries = summaries;
        this.runs = runs;
        this.configs = configs;
        this.objectMapper = objectMapper;
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
                components.findByRepositoryIdOrderByDisplayOrderAscNameAsc(repositoryId).stream()
                        .map(this::componentOf).toList(),
                latest,
                summary.map(RepositorySummary::getLastFullRunId).orElse(null),
                new RepositoryResponses.RepositoryFreshness(lastMeasured, lastFull),
                configs.findFirstByRepositoryIdOrderByVersionDesc(repositoryId)
                        .map(c -> c.getVersion()).orElse(null));
    }

    static RepositoryResponses.RepositoryItem itemOf(MonitoredRepository repository) {
        return new RepositoryResponses.RepositoryItem(repository.getId(), repository.fullName(),
                repository.getOwner(), repository.getName(), repository.getDefaultBranch(),
                repository.isMeasurePullRequests(), repository.isEnabled(),
                repository.getCreatedAt());
    }

    private RepositoryResponses.ComponentItem componentOf(RepositoryComponent component) {
        return new RepositoryResponses.ComponentItem(component.getName(), component.getLanguage(),
                objectMapper.readValue(component.getPathPatterns(), STRING_LIST));
    }

    private static RepositoryResponses.LatestRunSummary latestOf(Run run) {
        return new RepositoryResponses.LatestRunSummary(run.getId(), run.getCommitSha(),
                run.getBranch(), run.getStatus(), run.getVerdict(), run.getCompleteness(),
                run.getMeasuredAt());
    }
}
