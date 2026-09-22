package com.qualitygate.domain.gate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 検証済みの {@code .quality-gate.yml}。
 *
 * <p>YAML の生の構造ではなく、判定に必要な形に整えた表現を持つ。
 * 未指定の項目には既定値が入っており、利用側は常に値がある前提で書ける。
 */
public record GateConfigDocument(
        int version,
        String enforcement,
        String onMissingReport,
        Execution execution,
        List<String> exclusions,
        Map<String, MetricConfig> metrics) {

    public static final String SOURCE_DEFAULT = "DEFAULT";

    /**
     * @param skippableMetrics スキップ申告を受理してよい指標。ここに無い指標の
     *        申告は SKIP ではなく ERROR になる
     */
    public record Execution(
            Set<String> skippableMetrics,
            int fullMeasurementIntervalDays,
            Set<String> referenceOnlyEnvironments) {
    }

    /**
     * 指標ごとの設定。項目は指標によって異なるため、共通の器に値を入れる。
     *
     * @param enabled 判定対象か
     * @param values  しきい値など（キーは指標ごとに定義）
     */
    public record MetricConfig(boolean enabled, Map<String, Object> values) {

        public Optional<BigDecimal> number(String key) {
            Object value = values.get(key);
            if (value instanceof Number number) {
                return Optional.of(new BigDecimal(number.toString()));
            }
            return Optional.empty();
        }

        public Optional<String> text(String key) {
            Object value = values.get(key);
            return value instanceof String s ? Optional.of(s) : Optional.empty();
        }

        @SuppressWarnings("unchecked")
        public List<String> list(String key) {
            Object value = values.get(key);
            return value instanceof List<?> list ? (List<String>) list : List.of();
        }
    }

    public MetricConfig metric(String name) {
        return metrics.getOrDefault(name, new MetricConfig(true, Map.of()));
    }

    /** 設定ファイルが無い場合に使う既定値。要件定義書 6.5 の設定例に対応する。 */
    public static GateConfigDocument defaults() {
        Map<String, MetricConfig> metrics = new LinkedHashMap<>();
        metrics.put("branch_coverage", new MetricConfig(true, Map.of(
                "threshold", 75, "scope", "overall", "diff_threshold", 80)));
        metrics.put("mutation_score", new MetricConfig(true, Map.of("threshold", 60)));
        metrics.put("performance", new MetricConfig(true, Map.of(
                "p95_ms", 500, "arrival_rate_rps", 50, "error_rate_pct", 0.1)));
        metrics.put("vulnerabilities", new MetricConfig(true, Map.of(
                "max_critical", 0, "max_high", 0)));
        metrics.put("cyclomatic_complexity", new MetricConfig(true, Map.of(
                "max_complexity", 15, "warn_from", 11)));
        metrics.put("api_contract", new MetricConfig(true, Map.of(
                "min_success_rate", 100, "min_test_count", 1, "breaking_changes", 0)));
        metrics.put("accessibility", new MetricConfig(true, Map.of("max_critical", 0)));

        return new GateConfigDocument(1, "report-only", "fail",
                new Execution(Set.of("mutation_score", "performance"), 7,
                        Set.of("github-hosted")),
                List.of(), metrics);
    }
}
