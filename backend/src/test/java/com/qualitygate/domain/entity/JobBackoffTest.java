package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.JobStatus;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.platform.id.Uuid7;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JobBackoffTest {

    @Test
    void バックオフは指数的に伸びる() {
        assertThat(Job.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(Job.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(Job.backoff(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(Job.backoff(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(Job.backoff(5)).isEqualTo(Duration.ofMinutes(16));
        // 上限で頭打ちにする
        assertThat(Job.backoff(9)).isEqualTo(Duration.ofMinutes(16));
    }

    @Test
    void 一時的な失敗は再試行される() {
        Job job = newJob();
        job.markRunning("w", Instant.now());

        job.markFailed("timeout", true, Instant.now());

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getRunAfter()).isAfter(Instant.now());
    }

    @Test
    void 再実行しても直らない失敗は即座にDEADになる() {
        Job job = newJob();
        job.markRunning("w", Instant.now());

        // 形式不正などは試行回数を使い切らずに打ち切る。
        // そうしないと、原因が 5 回分のログに埋もれて見えなくなる。
        job.markFailed("形式不正", false, Instant.now());

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(job.getAttempts()).isEqualTo(1);
    }

    @Test
    void 最大試行回数に達したらDEADになる() {
        Job job = newJob();
        for (int i = 0; i < 5; i++) {
            job.markRunning("w", Instant.now());
            job.markFailed("timeout", true, Instant.now());
        }

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(job.getAttempts()).isEqualTo(5);
    }

    private static Job newJob() {
        return new Job(Uuid7.generate(), JobType.EVALUATE_RUN, "dedup", "{}");
    }
}
