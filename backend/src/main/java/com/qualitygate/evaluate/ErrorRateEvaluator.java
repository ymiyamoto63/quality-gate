package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M-04 エラー率（docs/metrics.md M-03）。
 *
 * <p>合格ライン（既定 0.1%）以下で PASS。5% を超える場合は基底クラスが ERROR にする。
 */
@Component
public class ErrorRateEvaluator extends PerformanceEvaluator {

    @Override
    public String metricId() {
        return GateThresholds.M_ERROR_RATE;
    }

    @Override
    String unit() {
        return "percent";
    }

    @Override
    Map<String, Object> threshold(GateThresholds.Performance limits) {
        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("operator", "<=");
        threshold.put("value", limits.errorRatePct());
        return threshold;
    }

    @Override
    Judgement judge(BigDecimal median, List<RawMeasurement> runs,
                    GateThresholds.Performance limits) {
        if (median.compareTo(limits.errorRatePct()) > 0) {
            return new Judgement(MeasurementStatus.FAIL, "エラー率 %s%% が合格ライン %s%% を超えています"
                    .formatted(plain(median), plain(limits.errorRatePct())));
        }
        return new Judgement(MeasurementStatus.PASS, "エラー率 %s%% は合格ライン %s%% 以内です"
                .formatted(plain(median), plain(limits.errorRatePct())));
    }
}
