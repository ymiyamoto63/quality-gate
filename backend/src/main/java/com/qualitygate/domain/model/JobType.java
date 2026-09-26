package com.qualitygate.domain.model;

/** ジョブ種別（docs/initial/05-architecture.md 4.2）。 */
public enum JobType {
    EVALUATE_RUN,
    REEVALUATE_RUN,
    CLEANUP_RETENTION,
    ABANDON_STALE_RUNS
}
