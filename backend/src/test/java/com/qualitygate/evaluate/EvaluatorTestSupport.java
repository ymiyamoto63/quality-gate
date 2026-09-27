package com.qualitygate.evaluate;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import com.qualitygate.platform.config.QualityGateProperties;
import com.qualitygate.platform.id.Uuid7;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 評価器のテストで使う入力の組み立て。 */
final class EvaluatorTestSupport {

    private EvaluatorTestSupport() {
    }

    static Run run() {
        return new Run(Uuid7.generate(), Uuid7.generate(),
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", "main",
                "ci", Instant.parse("2026-09-22T00:00:00Z"), 1);
    }

    static EvaluationContext context(NormalizedInput input) {
        return context(input, Map.of(), false);
    }

    static EvaluationContext context(NormalizedInput input,
                                     Map<String, BigDecimal> previousValues,
                                     boolean hasBaseline) {
        return new EvaluationContext(run(), GateThresholds.defaults(), input,
                previousValues, hasBaseline);
    }

    /**
     * 既定の合格ラインのうち、1 つの指標の値だけを差し替えた合格ライン。
     * キーは指標ごとの短い名前（環境変数 {@code QG_*} に対応する）。知らないキーは例外にする。
     */
    static GateThresholds thresholdsWith(String metric, Map<String, Object> values) {
        Map<String, Object> v = new HashMap<>(values);
        QualityGateProperties.Gate gate = new QualityGateProperties.Gate(null, null,
                decimal(take(v, metric, "branch_coverage", "threshold")),
                decimal(take(v, metric, "mutation_score", "threshold")),
                list(take(v, metric, "mutation_score", "components")),
                decimal(take(v, metric, "performance", "p95_ms")),
                decimal(take(v, metric, "performance", "arrival_rate_rps")),
                decimal(take(v, metric, "performance", "error_rate_pct")),
                list(take(v, metric, "performance", "scenarios")),
                integer(take(v, metric, "vulnerabilities", "max_critical")),
                integer(take(v, metric, "vulnerabilities", "max_high")),
                integer(take(v, metric, "cyclomatic_complexity", "max_complexity")),
                integer(take(v, metric, "api_contract", "breaking_changes")),
                integer(take(v, metric, "accessibility", "max_critical")),
                list(take(v, metric, "accessibility", "pages")),
                decimal(take(v, metric, "test_results", "min_success_rate")),
                integer(take(v, metric, "test_results", "min_test_count")),
                integer(take(v, metric, "test_results", "max_skipped_increase")),
                integer(take(v, metric, "secrets", "max_secrets")),
                integer(take(v, metric, "licenses", "max_forbidden")));
        if (!v.isEmpty()) {
            throw new IllegalArgumentException("合格ラインに無い項目です: " + metric + " " + v.keySet());
        }
        return GateThresholds.from(gate);
    }

    private static Object take(Map<String, Object> values, String metric, String target, String key) {
        return metric.equals(target) ? values.remove(key) : null;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        return (List<String>) value;
    }

    static NormalizedInput input(List<RawMeasurement> measurements,
                                 List<IdentifiedFinding> findings,
                                 Set<String> metricsWithData) {
        return new NormalizedInput(measurements, findings, metricsWithData, Map.of());
    }

    static RawMeasurement coverage(String component, String value) {
        return RawMeasurement.of("M-01", component,
                value == null ? null : new BigDecimal(value), "percent", Map.of());
    }

    static IdentifiedFinding vulnerability(String id, Severity severity) {
        RawFinding finding = new RawFinding("M-05", id, severity, id + " の脆弱性",
                "backend/pom.xml", null, "backend", id, Map.of());
        return new IdentifiedFinding("fp-" + id, finding);
    }

    static IdentifiedFinding function(String name, int complexity) {
        RawFinding finding = new RawFinding("M-06", "CyclomaticComplexity", Severity.INFO,
                "%s の循環的複雑度は %d です".formatted(name, complexity),
                "src/main/java/A.java", 10, "backend", "A.java#" + name,
                Map.of("complexity", complexity, "member", name));
        return new IdentifiedFinding("fp-" + name, finding);
    }
}
