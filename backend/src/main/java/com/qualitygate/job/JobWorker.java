package com.qualitygate.job;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * ジョブキューのポーラ。
 *
 * <p>専用のメッセージキューは導入しない。1 日 10〜30 Run の規模に対して
 * 運用コストが見合わないためである（docs/05-architecture.md 4.1）。
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final int BATCH_SIZE = 4;

    private final JobRepository jobs;
    private final List<JobHandler> handlers;
    private final String workerId = "worker-" + ProcessHandle.current().pid();

    public JobWorker(JobRepository jobs, List<JobHandler> handlers) {
        this.jobs = jobs;
        this.handlers = handlers;
    }

    @Scheduled(fixedDelayString = "${quality-gate.job-poll-interval:1000}")
    @Transactional
    public void poll() {
        List<Job> batch = jobs.lockNextPending(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        for (Job job : batch) {
            execute(job);
        }
    }

    private void execute(Job job) {
        Instant now = Instant.now();
        job.markRunning(workerId, now);
        try {
            handlerFor(job).handle(job);
            job.markSucceeded(Instant.now());
        } catch (RetryableJobException e) {
            log.warn("ジョブが一時的に失敗しました id={} type={} attempts={}",
                    job.getId(), job.getType(), job.getAttempts(), e);
            job.markFailed(e.getMessage(), true, Instant.now());
        } catch (RuntimeException e) {
            // 再実行しても同じ結果になる失敗は、試行回数を使い切らずに即座に DEAD とする。
            // そうしないと、原因が 5 回分のログに埋もれて見えなくなる。
            log.error("ジョブが恒久的に失敗しました id={} type={}", job.getId(), job.getType(), e);
            job.markFailed(e.getMessage(), false, Instant.now());
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
