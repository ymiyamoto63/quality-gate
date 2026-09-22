package com.qualitygate.evaluate;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.report.NormalizedInput;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 判定に必要な入力一式。
 *
 * @param previousValues 比較対象 Run の実測値（キーは {@link #key(String, String, String)}）
 */
public record EvaluationContext(
        Run run,
        GateThresholds thresholds,
        NormalizedInput input,
        Map<String, BigDecimal> previousValues,
        boolean hasBaseline) {

    public static String key(String metricId, String componentName) {
        return key(metricId, componentName, null);
    }

    /**
     * 前回値を引くキー。計測条件（{@code variant}）を含めるのは、条件の違う値どうしを
     * 比べないため。変更範囲だけの M-02 と全量の M-02 を比べた「前回比」は意味を持たない。
     */
    public static String key(String metricId, String componentName, String variant) {
        String base = metricId + "/" + (componentName == null ? "" : componentName);
        return variant == null ? base : base + "/" + variant;
    }

    public Optional<BigDecimal> previousValue(String metricId, String componentName) {
        return previousValue(metricId, componentName, null);
    }

    public Optional<BigDecimal> previousValue(String metricId, String componentName,
                                              String variant) {
        return Optional.ofNullable(previousValues.get(key(metricId, componentName, variant)));
    }
}
