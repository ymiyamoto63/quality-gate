package com.qualitygate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 監査ログ。<strong>追記のみ</strong>であり、更新・削除の経路を持たない。
 *
 * <p>{@link Immutable} により、アプリの実装ミスで行が書き換わることを JPA の側でも防ぐ。
 * 本番では DB ロールからも UPDATE / DELETE を剥奪する（V006）。
 *
 * <p>{@code actorLogin} を非正規化して持つのは、利用者を削除しても
 * 「誰が免除を登録したか」が失われないようにするため。
 */
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_login")
    private String actorLogin;

    @Column(nullable = false)
    private String action;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_value", columnDefinition = "jsonb")
    private String beforeValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_value", columnDefinition = "jsonb")
    private String afterValue;

    @Column(name = "client_ip", columnDefinition = "inet")
    @ColumnTransformer(read = "host(client_ip)", write = "cast(? as inet)")
    private String clientIp;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLog() {
    }

    @SuppressWarnings("java:S107")
    public AuditLog(UUID id, UUID actorUserId, String actorLogin, String action,
                    String targetType, String targetId, String beforeValue, String afterValue,
                    String clientIp, Instant occurredAt) {
        this.id = id;
        this.actorUserId = actorUserId;
        this.actorLogin = actorLogin;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.clientIp = clientIp;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getActorLogin() {
        return actorLogin;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public String getClientIp() {
        return clientIp;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
