package com.qualitygate.evaluate;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.report.NormalizedInput;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 判定に必要な入力一式。
 *
 * @param previousValues 比較対象 Run の実測値（キーは指標 ID + コンポーネント名）
 */
public record EvaluationContext(
        Run run,
        GateThresholds thresholds,
        NormalizedInput input,
        Map<String, BigDecimal> previousValues,
        boolean hasBaseline) {

    public static String key(String metricId, String componentName) {
        return metricId + "/" + (componentName == null ? "" : componentName);
    }

    public Optional<BigDecimal> previousValue(String metricId, String componentName) {
        return Optional.ofNullable(previousValues.get(key(metricId, componentName)));
    }
}
