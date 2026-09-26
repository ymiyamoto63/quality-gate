package com.qualitygate.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 日次バッチの起動（docs/initial/05-architecture.md 4.2）。保持期間の削除と、滞留した Run の後始末だけを行う。
 *
 * <p>失敗しても翌日にもう一度動くため、再試行の仕組みは持たない（D-27）。
 * 時刻は {@code quality-gate.schedule.*} で変えられる。{@code -} を指定すると無効になる。
 */
@Component
public class ScheduledMaintenance {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMaintenance.class);

    private final RetentionCleanup retention;
    private final StaleRunCleanup staleRuns;

    public ScheduledMaintenance(RetentionCleanup retention, StaleRunCleanup staleRuns) {
        this.retention = retention;
        this.staleRuns = staleRuns;
    }

    @Scheduled(cron = "${quality-gate.schedule.cleanup-retention:0 0 3 * * *}",
            zone = "${quality-gate.schedule.zone:Asia/Tokyo}")
    public void cleanupRetention() {
        runSafely("保持期間の削除", retention::run);
    }

    @Scheduled(cron = "${quality-gate.schedule.abandon-stale-runs:0 10 3 * * *}",
            zone = "${quality-gate.schedule.zone:Asia/Tokyo}")
    public void abandonStaleRuns() {
        runSafely("滞留した Run の後始末", staleRuns::run);
    }

    private static void runSafely(String name, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("日次バッチが失敗しました（翌日に再実行されます） task={}", name, e);
        }
    }
}
