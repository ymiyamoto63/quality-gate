package com.qualitygate.domain.model;

/** ジョブ種別（docs/initial/05-architecture.md 4.2）。 */
public enum JobType {
    EVALUATE_RUN,
    REEVALUATE_RUN,
    SEND_NOTIFICATION,
    DAILY_REEVALUATION,
    EXPIRE_WAIVERS,
    CHECK_FRESHNESS,
    CLEANUP_RETENTION,
    ABANDON_STALE_RUNS,
    /** 判定結果を GitHub の Check Run として出す（enforcement: check-run / blocking）。 */
    PUBLISH_CHECK_RUN
}
