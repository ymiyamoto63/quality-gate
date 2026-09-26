package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static com.qualitygate.evaluate.EvaluatorTestSupport.run;
import static com.qualitygate.evaluate.EvaluatorTestSupport.thresholdsWith;
import static com.qualitygate.evaluate.TestSuccessEvaluatorTest.report;
import static org.assertj.core.api.Assertions.assertThat;

class SkippedTestEvaluatorTest {

    private final SkippedTestEvaluator evaluator = new SkippedTestEvaluator();

    @Test
    void 比較対象が無ければ件数だけを記録して合格() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 10, 0, 0, 3, 0)),
                Map.of()).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isEqualByComparingTo("3");
        assertThat(result.unit()).isEqualTo("count");
        assertThat(result.reason()).contains("3 件").contains("増加は判定していません");
    }

    @Test
    void 前回より増えれば不合格() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 10, 0, 0, 3, 0)),
                Map.of(EvaluationContext.key("M-12", "backend"), BigDecimal.ONE)).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.reason()).contains("前回から 2 件増えました（1 件 → 3 件");
        assertThat(result.detail()).containsEntry("increase", 2L);
    }

    @Test
    void 増加の上限までは合格() {
        MetricResult result = evaluate(Map.of("max_skipped_increase", 2),
                List.of(report("backend", 10, 0, 0, 3, 0)),
                Map.of(EvaluationContext.key("M-12", "backend"), BigDecimal.ONE)).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
    }

    @Test
    void 既存のスキップが減らなくても増えなければ合格() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 10, 0, 0, 3, 0)),
                Map.of(EvaluationContext.key("M-12", "backend"), new BigDecimal("3"))).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.reason()).isEqualTo("スキップされたテストは 3 件です（前回 3 件）");
    }

    @Test
    void 件数の上限を超えれば比較対象が無くても不合格() {
        MetricResult result = evaluate(Map.of("max_skipped", 2),
                List.of(report("backend", 10, 0, 0, 3, 0)), Map.of()).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.threshold()).containsEntry("value", 2);
    }

    @Test
    void コンポーネントごとに前回と比べる() {
        List<MetricResult> results = evaluate(Map.of(), List.of(
                        report("backend", 10, 0, 0, 1, 0),
                        report("frontend", 10, 0, 0, 1, 0)),
                Map.of(EvaluationContext.key("M-12", "backend"), BigDecimal.ONE,
                        EvaluationContext.key("M-12", "frontend"), BigDecimal.ZERO));

        assertThat(results).extracting(MetricResult::componentName, MetricResult::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("backend", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("frontend", MeasurementStatus.FAIL));
    }

    @Test
    void スキップしたテストを違反として持つ() {
        RawFinding skipped = new RawFinding("M-12", "skipped", Severity.INFO,
                "A.a はスキップされました", null, null, "backend", "A#a", Map.of());
        EvaluationContext context = new EvaluationContext(run(),
                thresholdsWith("test_results", Map.of()),
                input(List.of(report("backend", 1, 0, 0, 1, 0)),
                        List.of(new IdentifiedFinding("fp-a", skipped)), List.of(), Set.of("M-12")),
                Map.of(), false);

        assertThat(evaluator.evaluate(context).getFirst().findingsToPersist()).hasSize(1);
    }

    private List<MetricResult> evaluate(Map<String, Object> config, List<RawMeasurement> measurements,
                                        Map<String, BigDecimal> previous) {
        Map<String, Object> values = new HashMap<>(config);
        EvaluationContext context = new EvaluationContext(run(),
                thresholdsWith("test_results", values),
                input(measurements, List.of(), List.of(), Set.of("M-11", "M-12")),
                previous, !previous.isEmpty());
        return evaluator.evaluate(context);
    }
}
