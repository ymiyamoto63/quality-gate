package com.qualitygate.domain.model;

/**
 * 指標単位の判定ステータス（docs/metrics.md 2.1）。
 *
 * <p>示す合否を明確にするため、「注意」や「未計測」の中間の段階は持たない。
 * 合格ラインを満たせば合格、満たさなければ不合格、測れなければ計測エラー（不合格として扱う）。
 */
public enum MeasurementStatus {
    PASS,
    FAIL,
    /** 提出されるはずの成果物が未提出、形式不正、または計測の条件を満たしていない。 */
    ERROR,
    /**
     * ツールの制約により、そのコンポーネントでは計測しようがない
     * （M-02 の frontend など。docs/metrics.md M-02）。合否には使わない。
     */
    NOT_APPLICABLE;

    /** Run 全体の合否に影響するか。 */
    public boolean affectsVerdict() {
        return this != NOT_APPLICABLE;
    }
}
