package com.qualitygate.domain.report;

import java.math.BigDecimal;
import java.util.Map;

/**
 * アダプタが読み取った素の指標値（判定前）。
 *
 * @param metricId      指標 ID（M-01 など）
 * @param componentName コンポーネント名。全体値の場合は null
 * @param value         実測値
 * @param unit          percent / ms / count / rps
 * @param detail        分母分子などの内訳。判定理由と画面表示に使う
 */
public record RawMeasurement(
        String metricId,
        String componentName,
        BigDecimal value,
        String unit,
        Map<String, Object> detail) {

    public static RawMeasurement of(String metricId, String componentName,
                                    BigDecimal value, String unit, Map<String, Object> detail) {
        return new RawMeasurement(metricId, componentName, value, unit, detail);
    }
}
