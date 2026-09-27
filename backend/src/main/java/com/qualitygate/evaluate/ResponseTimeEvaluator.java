package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.PerformanceSample;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M-03 応答時間 p95（docs/metrics.md M-03）。
 *
 * <p>全体とシナリオ単位の<strong>双方</strong>で判定する。全体だけを見ると、
 * リクエスト数の多い軽いエンドポイントが重いエンドポイントの遅さを薄めてしまう。
 *
 * <p>到達率（スループット）は達成値を競う指標ではなく、<strong>応答時間を測るための負荷条件</strong>として扱う。
 * 実測の成功スループットが設定到達率の 95% を下回った場合は、アプリが捌けずキューが詰まり
 * p95 が楽観的に出ている疑いがあるため、負荷条件を満たしていないとして計測エラーにする。
 */
@Component
public class ResponseTimeEvaluator extends PerformanceEvaluator {

    /** 3 回の p95 のばらつきがこれを超えたら、計測環境が不安定とみなす。 */
    static final BigDecimal UNSTABLE_CV_PCT = BigDecimal.valueOf(20);

    /** 実測の成功スループットがこの割合を下回ったら、負荷条件を満たしていないとみなす。 */
    static final BigDecimal ARRIVAL_RATIO = new BigDecimal("0.95");

    @Override
    public String metricId() {
        return GateThresholds.M_PERFORMANCE_P95;
    }

    @Override
    String unit() {
        return "ms";
    }

    @Override
    Map<String, Object> threshold(GateThresholds.Performance limits) {
        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("operator", "<=");
        threshold.put("value", limits.p95Ms());
        threshold.put("arrivalRateRps", limits.arrivalRateRps());
        return threshold;
    }

    @Override
    Map<String, Object> extraDetail(List<RawMeasurement> runs,
                                    GateThresholds.Performance limits) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scenarios", scenarioMedians(runs));
        detail.put("successRateRps", successRate(runs));
        detail.put("p95Runs", runs.stream().map(RawMeasurement::value).toList());
        detail.put("coefficientOfVariationPct", PerformanceSample.coefficientOfVariation(
                runs.stream().map(RawMeasurement::value).toList()));
        return detail;
    }

    @Override
    String undeterminableReason(List<RawMeasurement> runs, GateThresholds.Performance limits) {
        Map<String, BigDecimal> measured = scenarioMedians(runs);
        List<String> missing = limits.scenarios().stream()
                .filter(name -> !measured.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            return ("設定のシナリオ %s の p95 が summary にありません。"
                    + "k6 の thresholds に http_req_duration{scenario:<名前>} を定義してください")
                    .formatted(String.join(", ", missing));
        }
        BigDecimal throughput = successRate(runs);
        if (throughput.compareTo(limits.arrivalRateRps().multiply(ARRIVAL_RATIO)) < 0) {
            return ("成功スループット %s req/s が設定到達率 %s req/s の 95%% を下回っています。"
                    + "処理が追いつかず p95 が実態より良く出ている可能性があるため、判定しません")
                    .formatted(plain(throughput), plain(limits.arrivalRateRps()));
        }
        return null;
    }

    @Override
    Judgement judge(BigDecimal median, List<RawMeasurement> runs,
                    GateThresholds.Performance limits) {
        Map<String, BigDecimal> scenarios = scenarioMedians(runs);
        List<String> over = namesAbove(scenarios, limits.p95Ms());
        if (median.compareTo(limits.p95Ms()) > 0 || !over.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (median.compareTo(limits.p95Ms()) > 0) {
                parts.add("全体の p95 %sms".formatted(plain(median)));
            }
            over.forEach(name -> parts.add("シナリオ %s の p95 %sms"
                    .formatted(name, plain(scenarios.get(name)))));
            return new Judgement(MeasurementStatus.FAIL, "%s が合格ライン %sms を超えています"
                    .formatted(String.join("、", parts), plain(limits.p95Ms())));
        }

        String note = "";
        BigDecimal cv = PerformanceSample.coefficientOfVariation(
                runs.stream().map(RawMeasurement::value).toList());
        if (cv.compareTo(UNSTABLE_CV_PCT) > 0) {
            note = "（%d 回の変動係数が %s%% で 20%% を超えており、計測環境が不安定です）".formatted(runs.size(), plain(cv));
        }
        return new Judgement(MeasurementStatus.PASS, "p95 %sms は合格ライン %sms 以内です%s"
                .formatted(plain(median), plain(limits.p95Ms()), note));
    }

    /** 成功スループット（req/s）の中央値。 */
    static BigDecimal successRate(List<RawMeasurement> runs) {
        return PerformanceSample.median(runs.stream()
                .map(run -> run.detail().get("successRate") instanceof Number number
                        ? new BigDecimal(number.toString())
                        : BigDecimal.ZERO)
                .toList());
    }

    /** シナリオごとの p95 の中央値。 */
    @SuppressWarnings("unchecked")
    static Map<String, BigDecimal> scenarioMedians(List<RawMeasurement> runs) {
        Map<String, List<BigDecimal>> values = new TreeMap<>();
        for (RawMeasurement run : runs) {
            if (run.detail().get("scenarios") instanceof Map<?, ?> scenarios) {
                ((Map<String, Object>) scenarios).forEach((name, value) -> {
                    if (value instanceof Number number) {
                        values.computeIfAbsent(name, k -> new ArrayList<>())
                                .add(new BigDecimal(number.toString()));
                    }
                });
            }
        }
        Map<String, BigDecimal> medians = new TreeMap<>();
        values.forEach((name, list) -> medians.put(name, PerformanceSample.median(list)));
        return medians;
    }

    private static List<String> namesAbove(Map<String, BigDecimal> scenarios, BigDecimal limit) {
        return scenarios.entrySet().stream()
                .filter(e -> e.getValue().compareTo(limit) > 0)
                .map(Map.Entry::getKey).toList();
    }
}
