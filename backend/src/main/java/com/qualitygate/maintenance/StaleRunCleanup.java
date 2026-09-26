package com.qualitygate.maintenance;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.repo.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** finalize されないまま 24 時間滞留した Run を終端にする（毎日 {@link ScheduledMaintenance} から呼ぶ）。 */
@Component
public class StaleRunCleanup {

    private static final Logger log = LoggerFactory.getLogger(StaleRunCleanup.class);
    static final Duration STALE_RUN = Duration.ofHours(24);

    private final RunRepository runs;
    private final TransactionTemplate transactions;

    public StaleRunCleanup(RunRepository runs, TransactionTemplate transactions) {
        this.runs = runs;
        this.transactions = transactions;
    }

    public void run() {
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
