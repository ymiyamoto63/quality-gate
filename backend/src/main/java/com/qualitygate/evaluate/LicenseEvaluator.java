package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.IdentifiedFinding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M-13 ライセンス違反件数（docs/spec/02-metrics-spec.md M-13）。
 *
 * <p>数えるのは<strong>パッケージ</strong>。ライセンスの分類（Trivy の分類。緩い順に
 * unencumbered / permissive / notice / reciprocal / restricted / forbidden）で判定する。
 *
 * <p>1 つのパッケージに複数のライセンスが並ぶ場合は、<strong>選べる（OR）とみなし、最も緩いものを採る</strong>。
 * logback（EPL-2.0 または LGPL-2.1）や jakarta.*（EPL-2.0 または GPL-2.0 + Classpath 例外）のように、
 * デュアルライセンスを restricted として数えると、ほとんどの Java のアプリが不合格になる。
 * 分類が分からない（unknown）ライセンスは、他に分類の分かるものがあればそちらを採る。
 *
 * <p>判定の優先順位:
 * <ol>
 *   <li>forbidden のパッケージが上限（既定 0）を超えた、または restricted / unknown が上限（設定したときだけ）を超えた → FAIL</li>
 *   <li>restricted / unknown のパッケージがある → WARN（既定では件数で落とさない。利用形態で可否が変わるため）</li>
 *   <li>それ以外 → PASS</li>
 * </ol>
 *
 * <p>残す違反は forbidden / restricted / unknown になったパッケージのライセンスだけ。
 * ライセンスの走査は全パッケージを報告するため、全件を残すと違反でない行が大量に積まれる。
 */
@Component
public class LicenseEvaluator implements MetricEvaluator {

    /** 分類の厳しさ。値が大きいほど厳しい。unknown は分類の分かるものより後に回す。 */
    private static final Map<String, Integer> STRICTNESS = Map.of(
            "unencumbered", 0, "permissive", 0, "notice", 1, "reciprocal", 2,
            "restricted", 3, "forbidden", 4, "unknown", 5);

    @Override
    public String metricId() {
        return GateThresholds.M_LICENSES;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        GateThresholds.Licenses thresholds = context.thresholds().licenses();

        // パッケージ（とコンポーネント）ごとに、最も緩い分類を採る
        Map<String, List<IdentifiedFinding>> byPackage = new LinkedHashMap<>();
        for (IdentifiedFinding finding : context.input().headFindingsOf(metricId())) {
            String key = Objects.requireNonNullElse(finding.finding().componentName(), "") + "|"
                    + finding.finding().detail().getOrDefault("package", finding.finding().identity());
            byPackage.computeIfAbsent(key, k -> new ArrayList<>()).add(finding);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        List.of("forbidden", "restricted", "unknown", "reciprocal").forEach(c -> counts.put(c, 0));
        List<IdentifiedFinding> violations = new ArrayList<>();
        for (List<IdentifiedFinding> licenses : byPackage.values()) {
            String effective = licenses.stream().map(LicenseEvaluator::classificationOf)
                    .min((a, b) -> Integer.compare(strictness(a), strictness(b))).orElse("unknown");
            counts.computeIfPresent(effective, (k, v) -> v + 1);
            if (strictness(effective) >= strictness("restricted")) {
                // 採った分類のライセンスだけを違反として残す（選べる緩いライセンスは違反ではない）
                licenses.stream().filter(f -> classificationOf(f).equals(effective)).forEach(violations::add);
            }
        }

        int forbidden = counts.get("forbidden");
        int restricted = counts.get("restricted");
        int unknown = counts.get("unknown");
        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("operator", "<=");
        threshold.put("value", thresholds.maxForbidden());
        threshold.put("maxRestricted", thresholds.maxRestricted());
        threshold.put("maxUnknown", thresholds.maxUnknown());
        Map<String, Object> detail = new LinkedHashMap<>(counts);
        detail.put("packages", byPackage.size());

        MeasurementStatus status;
        String reason;
        if (forbidden > thresholds.maxForbidden() || exceeds(restricted, thresholds.maxRestricted())
                || exceeds(unknown, thresholds.maxUnknown())) {
            status = MeasurementStatus.FAIL;
            reason = "使えないライセンスのパッケージがあります（forbidden %d 件・restricted %d 件・分類不明 %d 件）"
                    .formatted(forbidden, restricted, unknown);
        } else if (restricted > 0 || unknown > 0) {
            status = MeasurementStatus.WARN;
            reason = "利用条件の確認が要るパッケージがあります（restricted %d 件・分類不明 %d 件）。配布の形態によっては使えません"
                    .formatted(restricted, unknown);
        } else {
            status = MeasurementStatus.PASS;
            reason = "forbidden / restricted のライセンスのパッケージはありません（%d パッケージを確認）"
                    .formatted(byPackage.size());
        }
        return List.of(MetricResult.of(metricId(), null, status, BigDecimal.valueOf(forbidden),
                "count", threshold, reason, detail, violations));
    }

    private static boolean exceeds(int count, Integer max) {
        return max != null && count > max;
    }

    private static String classificationOf(IdentifiedFinding finding) {
        Object value = finding.finding().detail().get("classification");
        return value instanceof String text && STRICTNESS.containsKey(text) ? text : "unknown";
    }

    private static int strictness(String classification) {
        return STRICTNESS.getOrDefault(classification, STRICTNESS.get("unknown"));
    }
}
