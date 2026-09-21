package com.qualitygate.domain.model;

public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** 最大試行回数に達した恒久的失敗。手動で確認・再実行する。 */
    DEAD
}
