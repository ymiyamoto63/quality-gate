package com.qualitygate.job;

import com.qualitygate.domain.entity.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * ジョブキューのポーラ。
 *
 * <p>専用のメッセージキューは導入しない。1 日 10〜30 Run の規模に対して
 * 運用コストが見合わないためである（docs/05-architecture.md 4.1）。
 *
 * <p><strong>このクラスに {@code @Transactional} は付けない。</strong>
 * トランザクションは {@link JobQueue} に閉じる（理由はそちらの Javadoc）。
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final int BATCH_SIZE = 4;

    private final JobQueue queue;
    private final List<JobHandler> handlers;

    public JobWorker(JobQueue queue, List<JobHandler> handlers) {
        this.queue = queue;
        this.handlers = handlers;
    }

    @Scheduled(fixedDelayString = "${quality-gate.job-poll-interval:1000}")
    public void poll() {
        for (UUID jobId : queue.claim(BATCH_SIZE)) {
            execute(jobId);
        }
    }

    private void execute(UUID jobId) {
        Job job = queue.load(jobId);
        try {
            handlerFor(job).handle(job);
            queue.markSucceeded(jobId);
        } catch (JobInputException e) {
            // 入力そのものが誤っている。再実行しても同じ結果になる。
            log.warn("ジョブの入力が不正です id={} type={} reason={}",
                    jobId, job.getType(), e.getMessage());
            queue.markFailed(jobId, e.getMessage(), false);
        } catch (RetryableJobException e) {
            log.warn("ジョブが一時的に失敗しました id={} type={} attempts={}",
                    jobId, job.getType(), job.getAttempts(), e);
            queue.markFailed(jobId, e.getMessage(), true);
        } catch (RuntimeException e) {
            // 再実行しても直らない失敗は、試行回数を使い切らずに即座に DEAD とする。
            // そうしないと、原因が試行回数分のログに埋もれて見えなくなる。
            log.error("ジョブが恒久的に失敗しました id={} type={}", jobId, job.getType(), e);
            queue.markFailed(jobId, e.getMessage(), false);
        }
    }

    private JobHandler handlerFor(Job job) {
        return handlers.stream()
                .filter(h -> h.supports(job.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "対応するハンドラがありません: " + job.getType()));
    }
}
