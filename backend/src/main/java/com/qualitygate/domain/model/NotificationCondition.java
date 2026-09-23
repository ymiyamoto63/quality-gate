package com.qualitygate.domain.model;

/** 判定の通知条件（FR-11-2）。 */
public enum NotificationCondition {
    /** 判定のたびに通知する。 */
    EVERY_RUN,
    /** 合格（または初回）から不合格に変わったときだけ通知する（既定。FR-11-1）。 */
    TRANSITION,
    /** 不合格のたびに通知する。 */
    FAIL_ONLY,
    /** 判定の通知をしない。免除の期限接近と計測途絶の警告も送らない。 */
    DISABLED
}
