package com.qualitygate.release;

import com.qualitygate.domain.metric.Decimals;
import com.qualitygate.domain.metric.Threshold;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 保存済みの合格ラインを、リリース判定と推移の表示に使う形にする。 */
final class ThresholdText {

    private ThresholdText() {
    }

    /**
     * 合格ラインを縦軸の値にする。前回からの増加で判定した行（DD-25 より前の M-10）は、値の軸に引ける線が無いため null。
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
            // DD-25 より前の M-10 は、件数そのものではなく比較元からの増加で判定していた
            return "前回から +%s%s 以内".formatted(Decimals.plain(threshold.value()), suffix(unit));
        }
        return "%s %s%s".formatted(symbol(threshold.operator()), Decimals.plain(threshold.value()), suffix(unit));
    }

    /**
     * 既定より緩いと記録された合格ラインの、既定の合格ライン（「≥ 75%」「関数ごとの循環的複雑度の上限 15」など）。
     * 緩くない（記録の無い）合格ラインでは null。
     *
     * <p>合格ラインの値そのものが緩ければその既定値を、上限・下限の補足（M-06 の関数ごとの上限など）が緩ければ
     * その名称と既定値を並べる。
     */
    static String defaultOf(Threshold threshold, String unit) {
        if (!threshold.isRelaxed()) {
            return null;
        }
        Threshold standard = threshold.defaults().orElse(null);
        if (standard == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (threshold.isValueLooserThan(standard)) {
            parts.add(of(standard, unit));
        }
        for (String key : threshold.looserLimitsThan(standard)) {
            Threshold.limitOf(key).ifPresent(limit ->
                    parts.add("%s %s".formatted(limit.label(), Decimals.plain(standard.extraValue(key)))));
        }
        parts.removeIf(Objects::isNull);
        return parts.isEmpty() ? of(standard, unit) : String.join("、", parts);
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
