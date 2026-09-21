package com.qualitygate.domain.model;

/**
 * 比較対象 Run との差分による違反の状態。
 *
 * <p>{@link #INITIAL} は比較対象が存在しない初回 Run 用。既存の違反を
 * すべて NEW と表示すると「この変更が大量の問題を持ち込んだ」という
 * 誤った印象を与えるため、区別する。
 */
public enum FindingState {
    NEW,
    CONTINUING,
    RESOLVED,
    INITIAL
}
