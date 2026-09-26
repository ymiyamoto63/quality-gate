package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawFinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static com.qualitygate.evaluate.EvaluatorTestSupport.run;
import static com.qualitygate.evaluate.EvaluatorTestSupport.thresholdsWith;
import static org.assertj.core.api.Assertions.assertThat;

class LicenseEvaluatorTest {

    private final LicenseEvaluator evaluator = new LicenseEvaluator();

    @Test
    void 緩いライセンスだけなら合格() {
        MetricResult result = evaluate(Map.of(), List.of(
                license("vue", "MIT", "notice"), license("axe-core", "MPL-2.0", "reciprocal")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isEqualByComparingTo("0");
        assertThat(result.detail()).containsEntry("packages", 2).containsEntry("reciprocal", 1);
        assertThat(result.findingsToPersist()).isEmpty();
    }

    @Test
    void デュアルライセンスは最も緩いものを採る() {
        // logback は EPL-2.0 か LGPL-2.1 を選べる。LGPL を理由に警告しない
        MetricResult result = evaluate(Map.of(), List.of(
                license("ch.qos.logback:logback-core", "LGPL-2.1-only", "restricted"),
                license("ch.qos.logback:logback-core", "EPL-2.0", "reciprocal")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.detail()).containsEntry("restricted", 0).containsEntry("reciprocal", 1);
    }

    @Test
    void forbiddenのパッケージがあれば不合格でそのライセンスだけを違反に残す() {
        MetricResult result = evaluate(Map.of(), List.of(
                license("left-pad", "WTFPL", "forbidden"),
                license("vue", "MIT", "notice")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.value()).isEqualByComparingTo("1");
        assertThat(result.findingsToPersist()).extracting(f -> f.finding().identity())
                .containsExactly("left-pad|WTFPL");
    }

    @Test
    void restrictedと分類不明は既定では警告() {
        MetricResult result = evaluate(Map.of(), List.of(
                license("some-lib", "GPL-3.0-only", "restricted"),
                license("odd-lib", "Custom", "unknown")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.detail()).containsEntry("restricted", 1).containsEntry("unknown", 1);
        assertThat(result.findingsToPersist()).hasSize(2);
    }

    @Test
    void restrictedの上限を設定すれば超えた分は不合格() {
        MetricResult result = evaluate(Map.of("max_restricted", 0), List.of(
                license("some-lib", "GPL-3.0-only", "restricted")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
    }

    @Test
    void 分類の分かるライセンスがあれば分類不明より優先する() {
        MetricResult result = evaluate(Map.of(), List.of(
                license("lib", "Custom", "unknown"), license("lib", "MIT", "notice")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
    }

    private MetricResult evaluate(Map<String, Object> config, List<IdentifiedFinding> findings) {
        EvaluationContext context = new EvaluationContext(run(), thresholdsWith("licenses", config),
                input(List.of(), findings, List.of(), Set.of("M-13")), Map.of(), false);
        List<MetricResult> results = evaluator.evaluate(context);
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    private static IdentifiedFinding license(String pkg, String license, String classification) {
        RawFinding finding = new RawFinding("M-13", pkg + ":" + license, Severity.INFO,
                pkg + " のライセンス " + license, "package-lock.json", null, null, pkg + "|" + license,
                Map.of("package", pkg, "license", license, "classification", classification));
        return new IdentifiedFinding("fp-" + pkg + license, finding);
    }
}
