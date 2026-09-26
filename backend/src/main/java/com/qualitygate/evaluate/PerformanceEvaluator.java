package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.PerformanceSample;
import com.qualitygate.domain.report.RawMeasurement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 性能指標（M-03 / M-04 / M-05）に共通する判定の骨組み（docs/spec/02-metrics-spec.md M-03）。
 *
 * <p>コンポーネントと計測環境の組ごとに 1 つの結果を出す。同じ組に届いた成果物は
 * 「同じ条件での繰り返し実行」とみなし、値の中央値で判定する。
 *
 * <p>判定の優先順位は次のとおり。上で決まったものは下を見ない。
 * <ol>
 *   <li>エラー率が 5% を超える → ERROR（負荷試験そのものが成立していない）</li>
 *   <li>指標固有の値の確定条件（M-03 のシナリオ欠落など） → ERROR</li>
 *   <li>しきい値に照らして PASS / WARN / FAIL</li>
 * </ol>
 */
abstract class PerformanceEvaluator implements MetricEvaluator {

    /** これを超えるエラー率では、応答時間もスループットも意味を持たない。 */
    static final BigDecimal BROKEN_ERROR_RATE = BigDecimal.valueOf(5);

    /** 仕様が定める実行回数。下回れば中央値の意味が弱まる。 */
    static final int EXPECTED_RUNS = 3;

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        Map<Key, List<RawMeasurement>> groups = new TreeMap<>();
        for (RawMeasurement measurement : context.input().measurementsOf(metricId())) {
            groups.computeIfAbsent(new Key(measurement.componentName(), measurement.variant()),
                    k -> new ArrayList<>()).add(measurement);
        }

        List<MetricResult> results = new ArrayList<>();
        groups.forEach((key, runs) -> results.add(evaluateGroup(key, runs, context)));
        return results;
    }

    private MetricResult evaluateGroup(Key key, List<RawMeasurement> runs,
                                       EvaluationContext context) {
        GateThresholds.Performance limits = context.thresholds().performance();
        BigDecimal value = PerformanceSample.median(runs.stream()
                .map(RawMeasurement::value).filter(Objects::nonNull).toList());
        BigDecimal errorRate = PerformanceSample.median(runs.stream()
                .map(r -> errorRateOf(r.detail())).toList());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runs", runs.size());
        detail.put("errorRatePct", errorRate);
        detail.put("environment", runs.getFirst().detail().get("environment"));
        detail.putAll(extraDetail(runs, limits));

        Map<String, Object> threshold = threshold(limits);
        if (errorRate.compareTo(BROKEN_ERROR_RATE) > 0) {
            return result(key, MeasurementStatus.ERROR, null, threshold,
                    "エラー率 %s%% が 5%% を超えています。負荷試験自体が成立していないため判定しません"
                            .formatted(plain(errorRate)), detail);
        }
        String undeterminable = undeterminableReason(runs, limits);
        if (undeterminable != null) {
            return result(key, MeasurementStatus.ERROR, null, threshold, undeterminable, detail);
        }

        Judgement judgement = judge(value, runs, limits);
        if (judgement.status() == MeasurementStatus.PASS && runs.size() < EXPECTED_RUNS) {
            judgement = new Judgement(MeasurementStatus.WARN, judgement.reason()
                    + "。ただし実行回数が %d 回で、仕様の %d 回に届きません"
                    .formatted(runs.size(), EXPECTED_RUNS));
        }
        return result(key, judgement.status(), value, threshold, judgement.reason(), detail);
    }

    private MetricResult result(Key key, MeasurementStatus status, BigDecimal value,
                                Map<String, Object> threshold, String reason,
                                Map<String, Object> detail) {
        return MetricResult.of(metricId(), key.component(), status, value, unit(), threshold,
                reason, detail, List.of()).withVariant(key.variant());
    }

    abstract String unit();

    abstract Map<String, Object> threshold(GateThresholds.Performance limits);

    /** 指標固有の内訳。 */
    Map<String, Object> extraDetail(List<RawMeasurement> runs,
                                    GateThresholds.Performance limits) {
        return Map.of();
    }

    /** 値を確定できない理由。確定できるなら null。 */
    String undeterminableReason(List<RawMeasurement> runs, GateThresholds.Performance limits) {
        return null;
    }

    abstract Judgement judge(BigDecimal median, List<RawMeasurement> runs,
                             GateThresholds.Performance limits);

    static BigDecimal errorRateOf(Map<String, Object> detail) {
        long requests = longOf(detail.get("requests"));
        long failed = longOf(detail.get("failedRequests"));
        if (requests == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(failed * 100L)
                .divide(BigDecimal.valueOf(requests), 4, java.math.RoundingMode.HALF_UP);
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    record Judgement(MeasurementStatus status, String reason) {
    }

    /** 判定をまとめる単位。null は "" として並べる（TreeMap は null キーを持てない）。 */
    record Key(String component, String variant) implements Comparable<Key> {

        @Override
        public int compareTo(Key other) {
            int byComponent = Objects.requireNonNullElse(component, "")
                    .compareTo(Objects.requireNonNullElse(other.component, ""));
            return byComponent != 0 ? byComponent
                    : Objects.requireNonNullElse(variant, "")
                            .compareTo(Objects.requireNonNullElse(other.variant, ""));
        }
    }
}
