package com.qualitygate.job;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.model.JobStatus;
import com.qualitygate.domain.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ジョブの取り出しと結果の記録。<strong>トランザクションはここだけに閉じる。</strong>
 *
 * <p>ジョブの実行そのものをトランザクションで包むと、次の 2 つの問題が起きる。
 * <ul>
 *   <li>成果物のパースやファイル読み取りの間、DB のトランザクションを保持し続ける</li>
 *   <li>ハンドラ内の {@code @Transactional} が例外でロールバック専用になると、
 *       外側のコミットが {@code UnexpectedRollbackException} で失敗し、
 *       <strong>ジョブの状態更新まで巻き戻って同じジョブを永久に再実行する</strong></li>
 * </ul>
 */
@Service
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    /** RUNNING のまま放置されたジョブを回収するまでの時間。 */
    private static final Duration STALE_AFTER = Duration.ofMinutes(15);

    private final JobRepository jobs;
    private final String workerId = "worker-" + ProcessHandle.current().pid();

    public JobQueue(JobRepository jobs) {
        this.jobs = jobs;
    }

    /**
     * 実行待ちジョブを RUNNING にして取り出す。
     *
     * <p>このトランザクションが commit した時点でジョブは RUNNING になり、
     * 他のポーラからは見えなくなる。
     */
    @Transactional
    public List<UUID> claim(int batchSize) {
        reclaimStale();
        List<Job> batch = jobs.lockNextPending(Instant.now(), PageRequest.of(0, batchSize));
        Instant now = Instant.now();
        batch.forEach(job -> job.markRunning(workerId, now));
        return batch.stream().map(Job::getId).toList();
    }

    @Transactional(readOnly = true)
    public Job load(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(
                () -> new IllegalStateException("ジョブが見つかりません: " + jobId));
    }

    @Transactional
    public void markSucceeded(UUID jobId) {
        jobs.findById(jobId).ifPresent(job -> job.markSucceeded(Instant.now()));
    }

    @Transactional
    public void markFailed(UUID jobId, String error, boolean retryable) {
        jobs.findById(jobId).ifPresent(job -> job.markFailed(error, retryable, Instant.now()));
    }

    /**
     * プロセスが落ちて RUNNING のまま残ったジョブを実行待ちに戻す。
     *
     * <p>これが無いと、異常終了したジョブは誰にも処理されず永久に残る。
     */
    private void reclaimStale() {
        List<Job> stale = jobs.findStale(Instant.now().minus(STALE_AFTER));
        if (stale.isEmpty()) {
            return;
        }
        log.warn("RUNNING のまま滞留したジョブを実行待ちに戻します 件数={}", stale.size());
        stale.forEach(job -> job.requeue(Instant.now()));
    }

    @Transactional(readOnly = true)
    public long countByStatus(JobStatus status) {
        return jobs.countByStatus(status);
    }
}
