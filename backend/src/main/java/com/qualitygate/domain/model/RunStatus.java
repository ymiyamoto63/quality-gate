package com.qualitygate.domain.model;

/**
 * Run の処理状態（docs/05-architecture.md 2.1）。
 *
 * <p>{@link #FAILED} は quality-gate 側の処理失敗であり、
 * 品質が合格ラインを満たさない {@link Verdict#FAIL} とは別物である。
 */
public enum RunStatus {
    CREATED,
    UPLOADING,
    FINALIZED,
    PROCESSING,
    EVALUATED,
    FAILED,
    ABANDONED;

    /** 成果物の追加を受け付けられる状態か。 */
    public boolean acceptsArtifacts() {
        return this == CREATED || this == UPLOADING;
    }
}
