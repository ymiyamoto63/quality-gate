package com.qualitygate.release;

/** リリース判定の結論。経営陣に示すため、「注意あり」のような中間の結論は持たない。 */
public enum ReleaseDecision {
    /** 判定したすべての指標が合格ラインを満たしている。 */
    RELEASABLE,
    /** 合格ラインを満たさない、または計測できなかった指標がある。 */
    NOT_RELEASABLE,
    /** そのコミットの判定済みの計測が無い。測っていないものを合格とはみなさない。 */
    NOT_MEASURED
}
