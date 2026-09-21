package com.qualitygate.domain.model;

/** ジョブ種別（docs/05-architecture.md 4.2）。 */
public enum JobType {
    EVALUATE_RUN,
    REEVALUATE_RUN,
    SEND_NOTIFICATION,
    DAILY_REEVALUATION,
    EXPIRE_WAIVERS,
    CHECK_FRESHNESS,
    CLEANUP_RETENTION,
    ABANDON_STALE_RUNS
}
