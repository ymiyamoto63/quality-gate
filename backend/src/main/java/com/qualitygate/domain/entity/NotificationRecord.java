package com.qualitygate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 通知の送信履歴。一意制約が再送の抑止そのものになる（docs/05-architecture.md 4.5）。
 */
@Entity
@Table(name = "notifications")
public class NotificationRecord {

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    @Id
    private UUID id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(nullable = false)
    private String event;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String status;

    @Column
    private String target;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    protected NotificationRecord() {
    }

    @SuppressWarnings("java:S107")
    public NotificationRecord(UUID id, UUID runId, UUID repositoryId, String event,
                              String channel, String target, String dedupKey) {
        this.id = id;
        this.runId = runId;
        this.repositoryId = repositoryId;
        this.event = event;
        this.channel = channel;
        this.target = target;
        this.dedupKey = dedupKey;
        this.status = FAILED;
    }

    /** 送信の結果を記録する。再送（再評価後の通知）では同じ行を更新する。 */
    public void record(String status, String externalId, String errorDetail, String dedupKey) {
        this.status = status;
        if (externalId != null) {
            this.externalId = externalId;
        }
        this.errorDetail = errorDetail;
        this.dedupKey = dedupKey;
        this.sentAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getEvent() {
        return event;
    }

    public String getChannel() {
        return channel;
    }

    public String getStatus() {
        return status;
    }

    public String getTarget() {
        return target;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
