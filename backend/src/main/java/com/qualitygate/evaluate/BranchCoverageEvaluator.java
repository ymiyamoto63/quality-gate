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
 * もう片方の低さを隠すためである（docs/spec/02-metrics-spec.md M-01）。
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
        MeasurementStatus status = statusOf(value, previous, thresholds);
        return MetricResult.of(metricId(), measurement.componentName(), status, value,
                "percent", threshold, reasonOf(status, value, previous, thresholds),
                measurement.detail(), List.of());
    }

    private static MeasurementStatus statusOf(BigDecimal value, BigDecimal previous,
                                              GateThresholds thresholds) {
        if (value.compareTo(thresholds.branchCoverageThreshold()) < 0) {
            return MeasurementStatus.FAIL;
        }
        if (value.compareTo(thresholds.branchCoverageWarnBelow()) < 0) {
            return MeasurementStatus.WARN;
        }
        // 合格ラインを満たしていても、前回より 1 ポイント以上落ちていれば注意を出す。
        // 下降が続いていることに気づかないまま、しきい値を割る直前まで放置されるのを防ぐ。
        if (previous != null && previous.subtract(value).compareTo(BigDecimal.ONE) >= 0) {
            return MeasurementStatus.WARN;
        }
        return MeasurementStatus.PASS;
    }

    private static String reasonOf(MeasurementStatus status, BigDecimal value,
                                   BigDecimal previous, GateThresholds thresholds) {
        String threshold = thresholds.branchCoverageThreshold().toPlainString();
        return switch (status) {
            case FAIL -> "しきい値 %s%% を下回っています（実測 %s%%）"
                    .formatted(threshold, value.toPlainString());
            case WARN -> previous != null
                    && previous.subtract(value).compareTo(BigDecimal.ONE) >= 0
                    ? "前回より %s ポイント低下しています（%s%% → %s%%）".formatted(
                            previous.subtract(value).setScale(2, RoundingMode.HALF_UP)
                                    .toPlainString(),
                            previous.toPlainString(), value.toPlainString())
                    : "しきい値 %s%% は満たしていますが、注意水準 %s%% を下回っています"
                            .formatted(threshold,
                                    thresholds.branchCoverageWarnBelow().toPlainString());
            default -> "しきい値 %s%% を満たしています（実測 %s%%）"
                    .formatted(threshold, value.toPlainString());
        };
    }
}
