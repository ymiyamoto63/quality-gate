package com.qualitygate.job;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.RepositorySummary;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.waiver.WaiverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 日次バッチのうち、判定と免除にかかわるもの。
 *
 * <ul>
 *   <li>{@code DAILY_REEVALUATION}: 各リポジトリの最新 Run を再評価する。新しい CVE の公開や
 *       しきい値の変更で、コード変更なしに判定が変わったことを検知する（UC-09）</li>
 *   <li>{@code EXPIRE_WAIVERS}: 期限切れの免除を無効化し、最新 Run を再評価する（FR-10-4）</li>
 *   <li>{@code ABANDON_STALE_RUNS}: finalize されないまま 24 時間滞留した Run を終端にする</li>
 *   <li>{@code CHECK_FRESHNESS}: 計測途絶と完全計測途絶を知らせる（通知は {@link DailyBatchListener}）</li>
 * </ul>
 */
@Component
public class DailyBatchJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DailyBatchJobHandler.class);
    static final Duration STALE_RUN = Duration.ofHours(24);

    private final MonitoredRepositoryRepository repositories;
    private final RepositorySummaryRepository summaries;
    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final JobEnqueuer enqueuer;
    private final WaiverService waivers;
    private final List<DailyBatchListener> listeners;
    private final TransactionTemplate transactions;

    @SuppressWarnings("java:S107")
    public DailyBatchJobHandler(MonitoredRepositoryRepository repositories,
                                RepositorySummaryRepository summaries, RunRepository runs,
                                ArtifactRecordRepository artifacts, JobEnqueuer enqueuer,
                                WaiverService waivers, List<DailyBatchListener> listeners,
                                TransactionTemplate transactions) {
        this.repositories = repositories;
        this.summaries = summaries;
        this.runs = runs;
        this.artifacts = artifacts;
        this.enqueuer = enqueuer;
        this.waivers = waivers;
        this.listeners = listeners;
        this.transactions = transactions;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.DAILY_REEVALUATION || type == JobType.EXPIRE_WAIVERS
                || type == JobType.ABANDON_STALE_RUNS || type == JobType.CHECK_FRESHNESS;
    }

    @Override
    public void handle(Job job) {
        switch (job.getType()) {
            case DAILY_REEVALUATION -> reevaluateLatestRuns();
            case EXPIRE_WAIVERS -> expireWaivers();
            case ABANDON_STALE_RUNS -> abandonStaleRuns();
            case CHECK_FRESHNESS -> listeners.forEach(l -> l.onFreshnessCheck(Instant.now()));
            default -> throw new IllegalStateException("対応しないジョブです: " + job.getType());
        }
    }

    private void reevaluateLatestRuns() {
        int queued = 0;
        for (MonitoredRepository repository : repositories.findByEnabledTrueOrderByOwnerAscNameAsc()) {
            Optional<Run> latest = summaries.findById(repository.getId())
                    .map(RepositorySummary::getLatestRunId)
                    .flatMap(runs::findById);
            if (latest.isEmpty()) {
                continue;
            }
            boolean deleted = artifacts.findByRunId(latest.get().getId()).stream()
                    .anyMatch(a -> a.getDeletedAt() != null);
            if (deleted) {
                // 成果物の無い Run は判定し直せない。最新の計測が 90 日以上途絶えている状態であり、
                // それは計測途絶の警告が別に知らせる
                continue;
            }
            transactions.executeWithoutResult(status ->
                    enqueuer.enqueueReevaluation(latest.get().getId(), "daily"));
            queued++;
        }
        log.info("日次の再評価を登録しました 件数={}", queued);
    }

    private void expireWaivers() {
        Instant now = Instant.now();
        waivers.expireOverdue(now);
        listeners.forEach(listener -> listener.onWaiversChecked(now));
    }

    private void abandonStaleRuns() {
        Integer count = transactions.execute(status -> {
            List<Run> stale = runs.findStaleRuns(Instant.now().minus(STALE_RUN));
            stale.forEach(Run::markAbandoned);
            return stale.size();
        });
        if (count != null && count > 0) {
            log.info("finalize されないまま滞留した Run を ABANDONED にしました 件数={}", count);
        }
    }
}
