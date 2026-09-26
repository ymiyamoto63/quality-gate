package com.qualitygate.job;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.repo.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 日次バッチ {@code ABANDON_STALE_RUNS}: finalize されないまま 24 時間滞留した Run を終端にする。 */
@Component
public class StaleRunJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(StaleRunJobHandler.class);
    static final Duration STALE_RUN = Duration.ofHours(24);

    private final RunRepository runs;
    private final TransactionTemplate transactions;

    public StaleRunJobHandler(RunRepository runs, TransactionTemplate transactions) {
        this.runs = runs;
        this.transactions = transactions;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.ABANDON_STALE_RUNS;
    }

    @Override
    public void handle(Job job) {
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
