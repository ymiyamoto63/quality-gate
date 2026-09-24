package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.RawMeasurement;
import com.qualitygate.domain.report.ReferenceValues;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * M-15 コード重複率（参考値。docs/initial/02-metrics-spec.md M-15）。
 *
 * <p>合格ラインを持たず、常に REFERENCE とする。基準値が見えてから合格ラインを決めるため、
 * まずは値とトレンドだけを残す。コンポーネントごとに、行数で合算して重複率を求める。
 */
@Component
public class DuplicationEvaluator implements MetricEvaluator {

    @Override
    public String metricId() {
        return GateThresholds.M_DUPLICATION;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        Map<String, long[]> byComponent = new TreeMap<>();
        for (RawMeasurement measurement : context.input().measurementsOf(metricId())) {
            long[] sum = byComponent.computeIfAbsent(
                    Objects.requireNonNullElse(measurement.componentName(), ""), k -> new long[3]);
            sum[0] += count(measurement, "lines");
            sum[1] += count(measurement, "duplicatedLines");
            sum[2] += count(measurement, "clones");
        }
        List<MetricResult> results = new ArrayList<>();
        byComponent.forEach((component, sum) -> {
            BigDecimal value = ReferenceValues.percentage(sum[1], sum[0]);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("lines", sum[0]);
            detail.put("duplicatedLines", sum[1]);
            detail.put("clones", sum[2]);
            String reason = value == null
                    ? "解析した行が 0 行のため、重複率を求められません（参考値）"
                    : "%d 行のうち %d 行が重複しています（%d 箇所。参考値のため合否には影響しません）"
                            .formatted(sum[0], sum[1], sum[2]);
            results.add(MetricResult.of(metricId(), component.isEmpty() ? null : component,
                    MeasurementStatus.REFERENCE, value, "percent", Map.of(), reason, detail, List.of()));
        });
        return results;
    }

    static long count(RawMeasurement measurement, String key) {
        return measurement.detail().get(key) instanceof Number number ? number.longValue() : 0;
    }
}
