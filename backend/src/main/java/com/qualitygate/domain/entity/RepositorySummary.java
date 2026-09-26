package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.Verdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * ダッシュボード用の読み取りモデル。判定完了時に更新する。
 *
 * <p>ダッシュボードはこの 1 テーブルを読むだけで描画でき、Run や Measurement を
 * 走査しない。書き込みは 1 日数十回、読み取りは毎日何度も行われるため、
 * 読み取り側を軽くする。
 */
@Entity
@Table(name = "repository_summaries")
public class RepositorySummary {

    @Id
    @Column(name = "repository_id")
    private UUID repositoryId;

    @Column(name = "latest_run_id")
    private UUID latestRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "latest_verdict")
    private Verdict latestVerdict;

    @Enumerated(EnumType.STRING)
    @Column(name = "latest_completeness")
    private Completeness latestCompleteness;

    @Column(name = "latest_measured_at")
    private Instant latestMeasuredAt;

    @Column(name = "last_full_run_id")
    private UUID lastFullRunId;

    /** 最後の完全計測（FR-06-3）。画面に常時表示する。 */
    @Column(name = "last_full_measured_at")
    private Instant lastFullMeasuredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_status", columnDefinition = "jsonb")
    private String categoryStatus;

    @Column(name = "open_critical_count", nullable = false)
    private int openCriticalCount;

    @Column(name = "open_high_count", nullable = false)
    private int openHighCount;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected RepositorySummary() {
    }

    public RepositorySummary(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    /**
     * 判定完了時に読み取りモデルを更新する。
     *
     * <p>完全計測（FULL）のときだけ {@code lastFull*} を進める。部分計測で上書きすると、
     * 「最後に全指標を測ったのはいつか」が失われる。
     */
    @SuppressWarnings("java:S107")
    public void update(UUID runId, Verdict verdict, Completeness completeness, Instant measuredAt,
                       String categoryStatus, int openCriticalCount, int openHighCount) {
        this.latestRunId = runId;
        this.latestVerdict = verdict;
        this.latestCompleteness = completeness;
        this.latestMeasuredAt = measuredAt;
        this.categoryStatus = categoryStatus;
        this.openCriticalCount = openCriticalCount;
        this.openHighCount = openHighCount;
        this.updatedAt = Instant.now();
        if (completeness == Completeness.FULL) {
            this.lastFullRunId = runId;
            this.lastFullMeasuredAt = measuredAt;
        }
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public UUID getLatestRunId() {
        return latestRunId;
    }

    public Verdict getLatestVerdict() {
        return latestVerdict;
    }

    public Completeness getLatestCompleteness() {
        return latestCompleteness;
    }

    public Instant getLatestMeasuredAt() {
        return latestMeasuredAt;
    }

    public UUID getLastFullRunId() {
        return lastFullRunId;
    }

    public Instant getLastFullMeasuredAt() {
        return lastFullMeasuredAt;
    }

    public String getCategoryStatus() {
        return categoryStatus;
    }

    public int getOpenCriticalCount() {
        return openCriticalCount;
    }

    public int getOpenHighCount() {
        return openHighCount;
    }
}
