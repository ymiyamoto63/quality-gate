package com.qualitygate.release;

/** リリース判定の結論（UC-10）。 */
public enum ReleaseDecision {
    /** 完全計測で、すべての指標が合格。 */
    RELEASABLE,
    /** 完全計測で不合格は無いが、注意の指標がある。 */
    RELEASABLE_WITH_WARNINGS,
    /** 不合格・計測エラーの指標がある（部分計測でも、測った範囲に不合格があれば確定する）。 */
    NOT_RELEASABLE,
    /**
     * 判定できない。未計測か、部分計測で不合格が無い場合。測っていない指標を合格とみなすと、
     * リリース判定として危険なため。
     */
    UNDETERMINED
}
