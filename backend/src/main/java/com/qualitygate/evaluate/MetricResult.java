package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.IdentifiedFinding;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 指標 1 件の判定結果。
 *
 * @param findingsToPersist 保存すべき違反。<strong>評価器が決める。</strong>
 *        M-07 のアダプタは全関数の CC 値を返すため、全件保存すると
 *        違反でない行が大量に積まれる。何が違反かは判定基準を知る側が決める。
 */
public record MetricResult(
        String metricId,
        String componentName,
        MeasurementStatus status,
        BigDecimal value,
        String unit,
        Map<String, Object> threshold,
        String reason,
        Map<String, Object> detail,
        List<IdentifiedFinding> findingsToPersist) {

    public static MetricResult of(String metricId, String componentName,
                                  MeasurementStatus status, BigDecimal value, String unit,
                                  Map<String, Object> threshold, String reason,
                                  Map<String, Object> detail,
                                  List<IdentifiedFinding> findingsToPersist) {
        return new MetricResult(metricId, componentName, status, value, unit,
                threshold, reason, detail, findingsToPersist);
    }

    /** 成果物が無い・読めないなど、値を確定できない場合。 */
    public static MetricResult error(String metricId, String reason) {
        return new MetricResult(metricId, null, MeasurementStatus.ERROR, null, null,
                Map.of(), reason, Map.of(), List.of());
    }

    public static MetricResult skipped(String metricId, String reason) {
        return new MetricResult(metricId, null, MeasurementStatus.SKIP, null, null,
                Map.of(), reason, Map.of(), List.of());
    }
}
