package com.qualitygate.release;

import com.qualitygate.domain.metric.Decimals;
import com.qualitygate.domain.metric.Threshold;

import java.math.BigDecimal;

/** 保存済みの合格ラインを、リリース判定と推移の表示に使う形にする。 */
final class ThresholdText {

    private ThresholdText() {
    }

    /**
     * 合格ラインを縦軸の値にする。前回からの増加で判定する指標（M-10）は、値の軸に引ける線が無いため null。
     */
    static BigDecimal lineOf(Threshold threshold) {
        return threshold.isIncrease() ? null : threshold.value();
    }

    /** 合格ラインを「≥ 75%」のような文字列にする。 */

    /** 比較の向きか値が無ければ（合格ラインを持たない結果・読めない行）null。 */
    static String of(Threshold threshold, String unit) {
        if (threshold.operator() == null || threshold.value() == null) {
            return null;
        }
        if (threshold.isIncrease()) {
            // M-10 は件数そのものではなく、比較元からの増加で判定する
            return "前回から +%s%s 以内".formatted(Decimals.plain(threshold.value()), suffix(unit));
        }
        return "%s %s%s".formatted(symbol(threshold.operator()), Decimals.plain(threshold.value()), suffix(unit));
    }

    private static String symbol(String operator) {
        return switch (operator) {
            case Threshold.AT_LEAST -> "≥";
            case Threshold.AT_MOST -> "≤";
            default -> operator;
        };
    }

    private static String suffix(String unit) {
        if (unit == null || unit.isEmpty()) {
            return "";
        }
        return switch (unit) {
            case "percent" -> "%";
            case "count" -> " 件";
            case "ms" -> "ms";
            case "score" -> " 点";
            default -> " " + unit;
        };
    }
}
