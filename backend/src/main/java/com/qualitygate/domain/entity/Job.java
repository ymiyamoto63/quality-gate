package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.JobStatus;
import com.qualitygate.domain.model.JobType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** DB をキューとして使うためのジョブ。 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobType type;

    /** 同一ジョブの多重登録を防ぐキー。EVALUATE_RUN では runId を用いる。 */
    @Column(name = "dedup_key")
    private String dedupKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "run_after", nullable = false)
    private Instant runAfter = Instant.now();

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Job() {
    }

    public Job(UUID id, JobType type, String dedupKey, String payload) {
        this.id = id;
        this.type = type;
        this.dedupKey = dedupKey;
        this.payload = payload;
    }

    public void markRunning(String worker, Instant at) {
        this.status = JobStatus.RUNNING;
        this.lockedBy = worker;
        this.lockedAt = at;
        this.attempts++;
        this.updatedAt = at;
    }

    /** 取り残されたジョブを実行待ちに戻す。試行回数は増やさない。 */
    public void requeue(Instant at) {
        this.status = JobStatus.PENDING;
        this.lockedBy = null;
        this.lockedAt = null;
        this.runAfter = at;
        this.updatedAt = at;
    }

    public void markSucceeded(Instant at) {
        this.status = JobStatus.SUCCEEDED;
        this.lockedBy = null;
        this.lockedAt = null;
        this.updatedAt = at;
    }

    /**
     * 失敗を記録し、再試行の可否を決める。
     *
     * <p>リトライ対象は一時的な障害に限る。形式不正のように再実行しても
     * 同じ結果になるものは {@code retryable=false} で即座に DEAD とし、
     * 失敗の原因が試行回数分のログに埋もれないようにする。
     */
    public void markFailed(String error, boolean retryable, Instant at) {
        this.lastError = error;
        this.lockedBy = null;
        this.lockedAt = null;
        this.updatedAt = at;
        if (!retryable || attempts >= maxAttempts) {
            this.status = JobStatus.DEAD;
        } else {
            this.status = JobStatus.PENDING;
            this.runAfter = at.plus(backoff(attempts));
        }
    }

    /** 指数バックオフ: 1, 2, 4, 8, 16 分。 */
    static Duration backoff(int attempts) {
        return Duration.ofMinutes(1L << Math.min(attempts - 1, 4));
    }

    public UUID getId() {
        return id;
    }

    public JobType getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getRunAfter() {
        return runAfter;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 恒久的失敗（DEAD）を手動で実行待ちに戻す。試行回数は数え直す。 */
    public void retry(Instant at) {
        this.status = JobStatus.PENDING;
        this.attempts = 0;
        this.runAfter = at;
        this.updatedAt = at;
    }

    public String getLastError() {
        return lastError;
    }
}
