package com.qualitygate.evaluate;

import com.qualitygate.domain.entity.Waiver;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.WaiverScope;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.NormalizedInput;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 判定時点で有効な免除（FR-10-3 / FR-10-6）。
 *
 * <ul>
 *   <li>違反の免除: 一致する違反を判定の入力から除く。違反を数えて判定する指標
 *       （M-06 / M-07 / M-09 / M-10）に効く。M-08 / M-11 / M-12 は件数の集計で判定するため、
 *       失敗・スキップしたテストを見逃すには指標の免除を使う</li>
 *   <li>指標の免除: 判定結果を参考値（REFERENCE）に置き換え、合否に影響させない。
 *       値と本来の判定理由は残す</li>
 * </ul>
 *
 * <p>免除した違反も一覧には残し、「免除中 N 件」として判定理由に併記する。
 * 免除は解決ではないため、見えなくしない。
 */
final class ActiveWaivers {

    private final Map<String, Waiver> byFinding;
    private final Map<String, Waiver> byMetric;

    private ActiveWaivers(Map<String, Waiver> byFinding, Map<String, Waiver> byMetric) {
        this.byFinding = byFinding;
        this.byMetric = byMetric;
    }

    static ActiveWaivers of(List<Waiver> waivers) {
        Map<String, Waiver> byFinding = new HashMap<>();
        Map<String, Waiver> byMetric = new HashMap<>();
        for (Waiver waiver : waivers) {
            if (waiver.getScope() == WaiverScope.FINDING) {
                byFinding.put(key(waiver.getMetricId(), waiver.getFingerprint()), waiver);
            } else {
                // 同じ指標に複数あれば期限の遅いものを代表にする
                byMetric.merge(waiver.getMetricId(), waiver,
                        (a, b) -> a.getExpiresAt().isAfter(b.getExpiresAt()) ? a : b);
            }
        }
        return new ActiveWaivers(byFinding, byMetric);
    }

    boolean coversFindings() {
        return !byFinding.isEmpty();
    }

    Optional<UUID> waiverOf(IdentifiedFinding finding) {
        return Optional.ofNullable(byFinding.get(key(finding.metricId(), finding.fingerprint())))
                .map(Waiver::getId);
    }

    /** 免除した違反を除いた入力。 */
    NormalizedInput removeFrom(NormalizedInput input) {
        if (byFinding.isEmpty()) {
            return input;
        }
        return new NormalizedInput(input.measurements(),
                input.headFindings().stream().filter(f -> waiverOf(f).isEmpty()).toList(),
                input.baseFindings(), input.metricsWithData(), input.parseErrors(),
                input.previousFingerprints());
    }

    /**
     * 免除を適用した判定結果に、免除した違反を戻す。
     *
     * <p>判定（ステータス・値・理由）は免除を適用した側を使い、保存する違反は
     * 適用しない側の判定で違反とされたものをすべて使う。
     */
    List<MetricResult> restoreWaivedFindings(List<MetricResult> waived,
                                             List<MetricResult> unwaived) {
        Map<String, MetricResult> original = new HashMap<>();
        unwaived.forEach(r -> original.put(resultKey(r), r));

        List<MetricResult> restored = new ArrayList<>();
        for (MetricResult result : waived) {
            MetricResult full = original.get(resultKey(result));
            if (full == null) {
                restored.add(result);
                continue;
            }
            Map<String, IdentifiedFinding> merged = new LinkedHashMap<>();
            full.findingsToPersist().forEach(f -> merged.put(f.fingerprint(), f));
            result.findingsToPersist().forEach(f -> merged.putIfAbsent(f.fingerprint(), f));
            long waivedCount = merged.values().stream()
                    .filter(f -> waiverOf(f).isPresent()).count();
            if (waivedCount == 0) {
                restored.add(result);
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>(result.detail());
            detail.put("waived", waivedCount);
            restored.add(new MetricResult(result.metricId(), result.componentName(),
                    result.status(), result.value(), result.unit(), result.threshold(),
                    result.reason() + "（免除中 %d 件を除く）".formatted(waivedCount),
                    detail, List.copyOf(merged.values()), result.variant()));
        }
        return restored;
    }

    /**
     * 指標の免除を適用する。判定済みの結果を参考値に置き換え、本来の判定を理由に残す。
     * 未計測・対象外は置き換えない（免除する判定がそもそも無い）。
     */
    List<MetricResult> applyMetricWaivers(List<MetricResult> results) {
        if (byMetric.isEmpty()) {
            return results;
        }
        List<MetricResult> applied = new ArrayList<>();
        for (MetricResult result : results) {
            Waiver waiver = byMetric.get(result.metricId());
            if (waiver == null || result.status() == MeasurementStatus.SKIP
                    || result.status() == MeasurementStatus.NOT_APPLICABLE) {
                applied.add(result);
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>(result.detail());
            detail.put("metricWaiver", Map.of(
                    "waiverId", waiver.getId().toString(),
                    "expiresAt", waiver.getExpiresAt().toString(),
                    "originalStatus", result.status().name()));
            applied.add(new MetricResult(result.metricId(), result.componentName(),
                    MeasurementStatus.REFERENCE, result.value(), result.unit(), result.threshold(),
                    "指標全体が免除されています（%s まで・%s）。本来の判定: %s — %s".formatted(
                            waiver.getExpiresAt().atOffset(ZoneOffset.UTC).toLocalDate(),
                            waiver.getReasonCategory().label(), result.status().name(),
                            result.reason()),
                    detail, result.findingsToPersist(), result.variant()));
        }
        return applied;
    }

    /** 指標の免除が設定されている指標 ID。 */
    Set<String> waivedMetrics() {
        return new LinkedHashSet<>(byMetric.keySet());
    }

    private static String key(String metricId, String fingerprint) {
        return metricId + "/" + fingerprint;
    }

    private static String resultKey(MetricResult result) {
        return result.metricId() + "/" + Objects.requireNonNullElse(result.componentName(), "")
                + "/" + Objects.requireNonNullElse(result.variant(), "");
    }
}
