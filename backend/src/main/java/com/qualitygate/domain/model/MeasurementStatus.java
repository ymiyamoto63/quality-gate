package com.qualitygate.domain.model;

/** 指標単位の判定ステータス（docs/01-requirements.md 6.2）。 */
public enum MeasurementStatus {
    PASS,
    WARN,
    FAIL,
    /** 設定で無効化、または CI が申告したスキップ。 */
    SKIP,
    /** 値は取得したが計測条件が統制外のため判定に用いない（参考値）。 */
    REFERENCE,
    /** 提出されるはずの成果物が未提出、または形式不正。 */
    ERROR;

    /** Run 全体の集約に影響するか。SKIP と REFERENCE は影響しない。 */
    public boolean affectsVerdict() {
        return this != SKIP && this != REFERENCE;
    }
}
