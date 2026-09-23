package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.NotificationCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * リポジトリごとの通知設定（FR-11-2 / FR-11-3）。通知はメールのみで行う。
 */
@Entity
@Table(name = "notification_settings")
public class NotificationSettings {

    @Id
    @Column(name = "repository_id")
    private UUID repositoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationCondition condition = NotificationCondition.TRANSITION;

    /** メールの宛先の JSON 配列。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "email_recipients", nullable = false, columnDefinition = "jsonb")
    private String emailRecipients = "[]";

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected NotificationSettings() {
    }

    public NotificationSettings(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public void setCondition(NotificationCondition condition) {
        this.condition = condition;
    }

    public void setEmailRecipients(String json) {
        this.emailRecipients = json;
    }

    public void touch(UUID by) {
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public NotificationCondition getCondition() {
        return condition;
    }

    public String getEmailRecipients() {
        return emailRecipients;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
