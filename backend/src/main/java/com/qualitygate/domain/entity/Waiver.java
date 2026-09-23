package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.WaiverReasonCategory;
import com.qualitygate.domain.model.WaiverScope;
import com.qualitygate.domain.model.WaiverStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 免除。「誰が、何を、なぜ、いつまで見逃すと決めたか」の記録。
 *
 * <p>期限（{@code expiresAt}）は DB で NOT NULL にしており、無期限の免除は
 * 構造的に作れない（FR-10-2）。Phase 1 は申請即承認のため {@code approvedBy} は
 * 登録者と同じ値を入れる。
 */
@Entity
@Table(name = "waivers")
public class Waiver {

    @Id
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaiverScope scope;

    @Column(name = "metric_id", nullable = false)
    private String metricId;

    @Column(length = 64)
    private String fingerprint;

    @Column
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_category", nullable = false)
    private WaiverReasonCategory reasonCategory;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaiverStatus status = WaiverStatus.ACTIVE;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    protected Waiver() {
    }

    @SuppressWarnings("java:S107")
    public Waiver(UUID id, UUID repositoryId, WaiverScope scope, String metricId,
                  String fingerprint, String title, WaiverReasonCategory reasonCategory,
                  String reason, UUID createdBy, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.repositoryId = repositoryId;
        this.scope = scope;
        this.metricId = metricId;
        this.fingerprint = fingerprint;
        this.title = title;
        this.reasonCategory = reasonCategory;
        this.reason = reason;
        this.createdBy = createdBy;
        this.approvedBy = createdBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void revoke(UUID by, Instant at) {
        this.status = WaiverStatus.REVOKED;
        this.revokedBy = by;
        this.revokedAt = at;
    }

    public void expire() {
        this.status = WaiverStatus.EXPIRED;
    }

    /** 判定に効いているか。状態が ACTIVE でも、期限を過ぎていれば効かない。 */
    public boolean isEffectiveAt(Instant at) {
        return status == WaiverStatus.ACTIVE && expiresAt.isAfter(at);
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public WaiverScope getScope() {
        return scope;
    }

    public String getMetricId() {
        return metricId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getTitle() {
        return title;
    }

    public WaiverReasonCategory getReasonCategory() {
        return reasonCategory;
    }

    public String getReason() {
        return reason;
    }

    public WaiverStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }
}
