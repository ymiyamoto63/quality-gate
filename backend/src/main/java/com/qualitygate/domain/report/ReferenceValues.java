package com.qualitygate.domain.report;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 参考値の指標（M-15 / M-17）の計算式。
 *
 * <p>アダプタ（1 ファイル分の値）と評価器（複数ファイルを合算した値）が同じ式を使うように、ここに 1 つだけ置く。
 */
public final class ReferenceValues {

    private ReferenceValues() {
    }

    /** 重複率（%）。解析した行が 0 行なら値を持たせない（0% ではない）。 */
    public static BigDecimal percentage(long duplicatedLines, long lines) {
        if (lines <= 0) {
            return null;
        }
        return BigDecimal.valueOf(duplicatedLines * 100L)
                .divide(BigDecimal.valueOf(lines), 2, RoundingMode.HALF_UP);
    }

    /** バイト数を KB（1024 バイト、小数 1 桁）にする。 */
    public static BigDecimal kilobytes(long bytes) {
        return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1024), 1, RoundingMode.HALF_UP);
    }
}
