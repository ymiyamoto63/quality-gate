package com.qualitygate.evaluate;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import com.qualitygate.platform.config.QualityGateProperties;
import com.qualitygate.platform.id.Uuid7;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
     * 既定の合格ラインのうち、指定した項目だけを差し替えた合格ライン。
     * キーは application.yml の {@code quality-gate.gate.*} と同じ名前（例: {@code secrets-max}）で、
     * 本番と同じ変換（カンマ区切りの一覧・既定値の補完）を通る。知らないキーは例外にする。
     */
    static GateThresholds thresholdsWith(Map<String, Object> properties) {
        Map<String, Object> source = new HashMap<>();
        properties.forEach((key, value) -> source.put("gate." + key,
                value instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf).toList())
                        : String.valueOf(value)));
        QualityGateProperties.Gate gate = new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("gate", Bindable.of(QualityGateProperties.Gate.class),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT));
        return GateThresholds.from(gate);
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
