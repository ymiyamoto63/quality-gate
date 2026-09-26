package com.qualitygate.platform.audit;

/**
 * 監査ログに記録する操作（FR-14-1）。
 *
 * <p>文字列として保存する。列挙の序数で持つと、値の追加で既存の記録の意味が変わる。
 * 削除した機能の操作（免除の WAIVER_* や NOTIFICATION_SETTINGS_UPDATED）も、過去の記録にはそのまま残る。
 */
public enum AuditAction {
    /** 利用者が 1 人もいない状態で、最初のログイン利用者を管理者にした（FR-13-5）。 */
    BOOTSTRAP_ADMIN,
    USER_ADDED,
    USER_ROLE_CHANGED,
    USER_STATUS_CHANGED,
    REPOSITORY_CREATED,
    REPOSITORY_UPDATED,
    COMPONENT_DEFINED,
    INGEST_TOKEN_ISSUED,
    INGEST_TOKEN_REVOKED,
    RUN_REEVALUATION_REQUESTED,
    RETENTION_SETTINGS_UPDATED
}
