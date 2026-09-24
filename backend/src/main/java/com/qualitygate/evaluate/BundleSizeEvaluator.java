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

/**
 * M-17 バンドルサイズ（gzip。参考値。docs/initial/02-metrics-spec.md M-17）。
 *
 * <p>合格ラインを持たず、常に REFERENCE とする。前回からの増減は Run 詳細の前回比で見る。
 * コンポーネントごとに 1 つのビルド結果を想定する。同じコンポーネントに複数届いたら最後のものを使う
 * （同じ画面を 2 回数えて倍にしないため）。
 */
@Component
public class BundleSizeEvaluator implements MetricEvaluator {

    @Override
    public String metricId() {
        return GateThresholds.M_BUNDLE_SIZE;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        Map<String, RawMeasurement> byComponent = new LinkedHashMap<>();
        for (RawMeasurement measurement : context.input().measurementsOf(metricId())) {
            byComponent.put(Objects.requireNonNullElse(measurement.componentName(), ""), measurement);
        }
        List<MetricResult> results = new ArrayList<>();
        byComponent.forEach((component, measurement) -> {
            long js = DuplicationEvaluator.count(measurement, "jsGzipBytes");
            long css = DuplicationEvaluator.count(measurement, "cssGzipBytes");
            BigDecimal previous = context.previousValue(metricId(), component.isEmpty() ? null : component)
                    .orElse(null);
            String change = previous == null || previous.signum() == 0 ? ""
                    : "、前回比 %+.1f%%".formatted(measurement.value().subtract(previous)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(previous, 1, java.math.RoundingMode.HALF_UP));
            String reason = "JavaScript %s KB・CSS %s KB（gzip 後%s。参考値のため合否には影響しません）"
                    .formatted(ReferenceValues.kilobytes(js).toPlainString(),
                            ReferenceValues.kilobytes(css).toPlainString(), change);
            results.add(MetricResult.of(metricId(), component.isEmpty() ? null : component,
                    MeasurementStatus.REFERENCE, measurement.value(), measurement.unit(), Map.of(), reason,
                    measurement.detail(), List.of()));
        });
        return results;
    }
}
