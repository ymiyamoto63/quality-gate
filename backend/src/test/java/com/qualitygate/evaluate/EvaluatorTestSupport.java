package com.qualitygate.evaluate;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.gate.GateConfigDocument;
import com.qualitygate.domain.model.RunnerType;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import com.qualitygate.platform.id.Uuid7;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
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
                RunnerType.SELF_HOSTED, "ci", Instant.parse("2026-09-22T00:00:00Z"), 1);
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

    /** 既定の設定のうち、1 つの指標の設定だけを差し替えた合格ライン。 */
    static GateThresholds thresholdsWith(String metric, Map<String, Object> values) {
        GateConfigDocument defaults = GateConfigDocument.defaults();
        Map<String, GateConfigDocument.MetricConfig> metrics =
                new LinkedHashMap<>(defaults.metrics());
        metrics.put(metric, new GateConfigDocument.MetricConfig(true, values));
        return GateThresholds.from(new GateConfigDocument(defaults.version(),
                defaults.enforcement(), defaults.onMissingReport(), defaults.execution(),
                defaults.exclusions(), metrics));
    }

    static NormalizedInput input(List<RawMeasurement> measurements,
                                 List<IdentifiedFinding> head,
                                 List<IdentifiedFinding> base,
                                 Set<String> metricsWithData) {
        return new NormalizedInput(measurements, head, base, metricsWithData, Map.of());
    }

    static RawMeasurement coverage(String component, String value) {
        return RawMeasurement.of("M-01", component,
                value == null ? null : new BigDecimal(value), "percent", Map.of());
    }

    static IdentifiedFinding vulnerability(String id, Severity severity) {
        RawFinding finding = new RawFinding("M-06", id, severity, id + " の脆弱性",
                "backend/pom.xml", null, "backend", id, Map.of());
        return new IdentifiedFinding("fp-" + id, finding);
    }

    static IdentifiedFinding function(String name, int complexity) {
        RawFinding finding = new RawFinding("M-07", "CyclomaticComplexity", Severity.INFO,
                "%s の循環的複雑度は %d です".formatted(name, complexity),
                "src/main/java/A.java", 10, "backend", "A.java#" + name,
                Map.of("complexity", complexity, "member", name));
        return new IdentifiedFinding("fp-" + name, finding);
    }
}
