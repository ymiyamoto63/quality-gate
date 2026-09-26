package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M-16 Lighthouse パフォーマンススコア（参考値。docs/initial/02-metrics-spec.md M-16）。
 *
 * <p>Lighthouse の値は 1 回ごとの揺れが大きいため、<strong>画面ごとに複数回分の中央値</strong>を取る
 * （M-03 の 3 回実行の中央値と同じ考え方）。値は<strong>最も低い画面</strong>のパフォーマンススコア。
 * 平均にすると、1 画面だけ重くなっても値がほとんど動かない。画面ごとの内訳（アクセシビリティ・
 * ベストプラクティス・SEO のスコアと LCP / CLS / TBT）は detail に残す。
 *
 * <p>合格ラインを持たず、常に REFERENCE とする。
 */
@Component
public class LighthouseEvaluator implements MetricEvaluator {

    private static final List<String> MEDIANS = List.of(
            "performance", "accessibility", "bestPractices", "seo", "lcpMs", "cls", "tbtMs", "fcpMs");

    @Override
    public String metricId() {
        return GateThresholds.M_LIGHTHOUSE;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        Map<String, List<RawMeasurement>> byPage = new TreeMap<>();
        for (RawMeasurement measurement : context.input().measurementsOf(metricId())) {
            String page = String.valueOf(measurement.detail().getOrDefault("page", "/"));
            byPage.computeIfAbsent(page, k -> new ArrayList<>()).add(measurement);
        }
        if (byPage.isEmpty()) {
            return List.of();
        }

        Map<String, Object> pages = new LinkedHashMap<>();
        String worstPage = null;
        BigDecimal worst = null;
        for (Map.Entry<String, List<RawMeasurement>> entry : byPage.entrySet()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            for (String key : MEDIANS) {
                median(entry.getValue(), key).ifPresent(value -> summary.put(key, value));
            }
            summary.put("runs", entry.getValue().size());
            pages.put(entry.getKey(), summary);
            BigDecimal performance = (BigDecimal) summary.get("performance");
            if (performance != null && (worst == null || performance.compareTo(worst) < 0)) {
                worst = performance;
                worstPage = entry.getKey();
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pages", pages);
        detail.put("worstPage", worstPage);
        String reason = "最も低い画面は %s の %s 点（画面ごとに %s 回分の中央値。参考値のため合否には影響しません）"
                .formatted(worstPage, worst == null ? "—" : worst.toPlainString(),
                        byPage.values().stream().map(List::size).distinct().sorted()
                                .map(String::valueOf).reduce((a, b) -> a + "〜" + b).orElse("0"));
        return List.of(MetricResult.of(metricId(), byPage.values().iterator().next().getFirst().componentName(),
                MeasurementStatus.REFERENCE, worst, "score", Map.of(), reason, detail, List.of()));
    }

    /** 中央値。偶数個なら中央の 2 つの平均。値の無い回は除く。 */
    static java.util.Optional<BigDecimal> median(List<RawMeasurement> runs, String key) {
        List<BigDecimal> values = runs.stream()
                .map(m -> m.detail().get(key))
                .filter(Number.class::isInstance)
                .map(v -> new BigDecimal(v.toString()))
                .sorted(Comparator.naturalOrder())
                .toList();
        if (values.isEmpty()) {
            return java.util.Optional.empty();
        }
        int middle = values.size() / 2;
        BigDecimal median = values.size() % 2 == 1 ? values.get(middle)
                : values.get(middle - 1).add(values.get(middle)).divide(BigDecimal.valueOf(2), 3, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
        return java.util.Optional.of(median);
    }
}
