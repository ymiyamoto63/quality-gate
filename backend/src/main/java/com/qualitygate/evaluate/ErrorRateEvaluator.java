package com.qualitygate.evaluate;

import com.qualitygate.domain.metric.Decimals;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.Threshold;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * M-04 エラー率（docs/metrics.md M-03）。
 *
 * <p>合格ライン（既定 0.1%）以下で PASS。5% を超える場合は基底クラスが ERROR にする。
 */
@Component
public class ErrorRateEvaluator extends PerformanceEvaluator {

    @Override
    public String metricId() {
        return MetricCatalog.M_ERROR_RATE;
    }

    @Override
    String unit() {
        return "percent";
    }

    @Override
    Threshold threshold(GateThresholds.Performance limits) {
        return Threshold.atMost(limits.errorRatePct());
    }

    @Override
    Judgement judge(BigDecimal median, List<RawMeasurement> runs,
                    GateThresholds.Performance limits) {
        if (median.compareTo(limits.errorRatePct()) > 0) {
            return new Judgement(MeasurementStatus.FAIL, "エラー率 %s%% が合格ライン %s%% を超えています"
                    .formatted(Decimals.plain(median), Decimals.plain(limits.errorRatePct())));
        }
        return new Judgement(MeasurementStatus.PASS, "エラー率 %s%% は合格ライン %s%% 以内です"
                .formatted(Decimals.plain(median), Decimals.plain(limits.errorRatePct())));
    }
}
