package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M-04 スループット（docs/initial/02-metrics-spec.md M-03「スループットの扱い」）。
 *
 * <p>スループットは達成値を競う指標ではなく、<strong>応答時間を測るための負荷条件</strong>である。
 * 負荷を結果変数にすると、負荷が低いほど応答時間が良く見えてしまう。
 * そのため不合格（FAIL）は出さない。実測が到達率の 95% を下回った場合は、アプリが
 * 捌けずキューが詰まっており p95 が楽観的に出ている疑いがあるため、WARN を付ける。
 */
@Component
public class ThroughputEvaluator extends PerformanceEvaluator {

    static final BigDecimal WARN_RATIO = new BigDecimal("0.95");

    @Override
    public String metricId() {
        return GateThresholds.M_THROUGHPUT;
    }

    @Override
    String unit() {
        return "rps";
    }

    @Override
    Map<String, Object> threshold(GateThresholds.Performance limits) {
        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("operator", ">=");
        threshold.put("value", limits.arrivalRateRps().multiply(WARN_RATIO));
        threshold.put("arrivalRateRps", limits.arrivalRateRps());
        return threshold;
    }

    @Override
    Judgement judge(BigDecimal median, List<RawMeasurement> runs,
                    GateThresholds.Performance limits) {
        BigDecimal floor = limits.arrivalRateRps().multiply(WARN_RATIO);
        if (median.compareTo(floor) < 0) {
            return new Judgement(MeasurementStatus.WARN,
                    ("成功スループット %s req/s が設定到達率 %s req/s の 95%% を下回っています。"
                            + "処理が追いつかず、p95 が実態より良く出ている可能性があります")
                            .formatted(plain(median), plain(limits.arrivalRateRps())));
        }
        return new Judgement(MeasurementStatus.PASS,
                "設定到達率 %s req/s に対し、成功スループット %s req/s で計測しました"
                        .formatted(plain(limits.arrivalRateRps()), plain(median)));
    }
}
