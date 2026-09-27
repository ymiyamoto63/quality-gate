package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M-01 ブランチカバレッジ。
 *
 * <p><strong>コンポーネントを合算しない。</strong>片方の高いカバレッジが
 * もう片方の低さを隠すためである（docs/metrics.md M-01）。
 */
@Component
public class BranchCoverageEvaluator implements MetricEvaluator {

    @Override
    public String metricId() {
        return GateThresholds.M_BRANCH_COVERAGE;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        GateThresholds thresholds = context.thresholds();
        List<MetricResult> results = new ArrayList<>();

        for (RawMeasurement measurement : context.input().measurementsOf(metricId())) {
            results.add(evaluateOne(measurement, context, thresholds));
        }
        return results;
    }

    private MetricResult evaluateOne(RawMeasurement measurement, EvaluationContext context,
                                     GateThresholds thresholds) {
        Map<String, Object> threshold = Map.of(
                "operator", ">=", "value", thresholds.branchCoverageThreshold());
        BigDecimal previous = context
                .previousValue(metricId(), measurement.componentName()).orElse(null);

        // 分岐が 0 個の場合は 100% とせず値を持たせない。
        // 100% と報告すると、分岐のないコンポーネントが合格を稼いでしまう。
        if (measurement.value() == null) {
            return MetricResult.of(metricId(), measurement.componentName(),
                    MeasurementStatus.PASS, null, "percent", threshold,
                    "分岐がないため判定対象がありません", measurement.detail(), List.of());
        }

        BigDecimal value = measurement.value().setScale(2, RoundingMode.HALF_UP);
        String limit = thresholds.branchCoverageThreshold().stripTrailingZeros().toPlainString();
        boolean passed = value.compareTo(thresholds.branchCoverageThreshold()) >= 0;
        String reason = (passed ? "合格ライン %s%% を満たしています（実測 %s%%）" : "合格ライン %s%% を下回っています（実測 %s%%）")
                .formatted(limit, value.toPlainString())
                + dropNote(previous, value);
        return MetricResult.of(metricId(), measurement.componentName(),
                passed ? MeasurementStatus.PASS : MeasurementStatus.FAIL, value,
                "percent", threshold, reason, measurement.detail(), List.of());
    }

    /** 前回より 1 ポイント以上落ちていれば、合否とは別に書き添える（下降が続いていることに気づけるように）。 */
    private static String dropNote(BigDecimal previous, BigDecimal value) {
        if (previous == null || previous.subtract(value).compareTo(BigDecimal.ONE) < 0) {
            return "";
        }
        return "。前回より %s ポイント低下しています（%s%% → %s%%）".formatted(
                previous.subtract(value).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                previous.toPlainString(), value.toPlainString());
    }
}
