package com.qualitygate.job;

import com.qualitygate.domain.model.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * 日次バッチの起動（docs/05-architecture.md 4.2）。
 *
 * <p>ここではジョブを積むだけで、処理はジョブキューのワーカーが行う。
 * 鍵に日付を含めるため、プロセスを複数動かしても同じ日のジョブは 1 つにまとまる。
 * 失敗時のリトライも通常のジョブと同じ仕組みに乗る。
 *
 * <p>時刻は {@code quality-gate.schedule.*} で変えられる。{@code -} を指定すると無効になる。
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final JobEnqueuer enqueuer;
    private final ZoneId zone;

    public ScheduledJobs(JobEnqueuer enqueuer,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${quality-gate.schedule.zone:Asia/Tokyo}") String zone) {
        this.enqueuer = enqueuer;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "${quality-gate.schedule.daily-reevaluation:0 0 2 * * *}",
            zone = "${quality-gate.schedule.zone:Asia/Tokyo}")
    public void dailyReevaluation() {
        enqueue(JobType.DAILY_REEVALUATION);
    }

    @Scheduled(cron = "${quality-gate.schedule.expire-waivers:0 10 2 * * *}",
            zone = "${quality-gate.schedule.zone:Asia/Tokyo}")
    public void expireWaivers() {
        enqueue(JobType.EXPIRE_WAIVERS);
    }

    @Scheduled(cron = "${quality-gate.schedule.cleanup-retention:0 0 3 * * *}",
            zone = "${quality-gate.schedule.zone:Asia/Tokyo}")
    public void cleanupRetention() {
        enqueue(JobType.CLEANUP_RETENTION);
    }

    @Scheduled(cron = "${quality-gate.schedule.abandon-stale-runs:0 10 3 * * *}",
            zone = "${quality-gate.schedule.zone:Asia/Tokyo}")
    public void abandonStaleRuns() {
        enqueue(JobType.ABANDON_STALE_RUNS);
    }

    @Scheduled(cron = "${quality-gate.schedule.check-freshness:0 0 9 * * *}",
            zone = "${quality-gate.schedule.zone:Asia/Tokyo}")
    public void checkFreshness() {
        enqueue(JobType.CHECK_FRESHNESS);
    }

    private void enqueue(JobType type) {
        String day = LocalDate.now(zone).toString();
        enqueuer.enqueue(type, type.name() + ":" + day, Map.of("date", day));
        log.info("日次バッチを登録しました type={} date={}", type, day);
    }
}
