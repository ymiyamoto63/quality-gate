package com.qualitygate.domain.model;

/** 指標単位の判定ステータス（docs/initial/01-requirements.md 6.2）。 */
public enum MeasurementStatus {
    PASS,
    WARN,
    FAIL,
    /** 設定で無効化、または CI が申告したスキップ。 */
    SKIP,
    /** 提出されるはずの成果物が未提出、または形式不正。 */
    ERROR,
    /**
     * ツールの制約により、そのコンポーネントでは計測しようがない
     * （M-02 の frontend など。docs/initial/02-metrics-spec.md M-02）。
     *
     * <p>{@link #SKIP} と分けるのは、SKIP が「今回は測らなかった」であるのに対し、
     * こちらは「この先も測る予定がない」ためである。同じ表示にすると、
     * 未計測の積み残しと誤読される。部分計測（PARTIAL）の理由にもならない。
     */
    NOT_APPLICABLE;

    /** Run 全体の集約に影響するか。SKIP・NOT_APPLICABLE は影響しない。 */
    public boolean affectsVerdict() {
        return this != SKIP && this != NOT_APPLICABLE;
    }
}
