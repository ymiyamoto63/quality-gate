package com.qualitygate.evaluate;

import com.qualitygate.domain.metric.Threshold;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
    void スキップが無ければ合格() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 10, 0, 0, 0, 0)),
                Map.of()).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isEqualByComparingTo("0");
        assertThat(result.unit()).isEqualTo("count");
        assertThat(result.reason()).isEqualTo("スキップされたテストはありません");
    }

    @Test
    void 比較対象が無くても件数が上限を超えれば不合格() {
        // 初回の計測でも、同じコミットなら同じ判定になる（前回の有無で合否を変えない）
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 10, 0, 0, 3, 0)),
                Map.of()).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.value()).isEqualByComparingTo("3");
        assertThat(result.reason()).startsWith("スキップされたテストが 3 件あります（上限 0 件）");
        assertThat(result.detail()).doesNotContainKey("increase");
    }

    @Test
    void 上限までは合格() {
        MetricResult result = evaluate(Map.of("skipped-tests-max", 3),
                List.of(report("backend", 10, 0, 0, 3, 0)), Map.of()).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.reason()).isEqualTo("スキップされたテストは 3 件です（上限 3 件）");
    }

    @Test
    void 前回からの増加は書き添えるだけで合否に使わない() {
        MetricResult result = evaluate(Map.of("skipped-tests-max", 3),
                List.of(report("backend", 10, 0, 0, 3, 0)),
                Map.of(EvaluationContext.key("M-10", "backend"), BigDecimal.ONE)).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.reason()).isEqualTo(
                "スキップされたテストは 3 件です（上限 3 件）。前回より 2 件増えています（1 件 → 3 件）");
        assertThat(result.detail()).containsEntry("increase", 2L);
    }

    @Test
    void 前回から増えていなくても上限を超えれば不合格() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 10, 0, 0, 3, 0)),
                Map.of(EvaluationContext.key("M-10", "backend"), new BigDecimal("3"))).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.reason()).doesNotContain("前回");
        assertThat(result.detail()).containsEntry("increase", 0L);
    }

    @Test
    void 合格ラインは件数の上限で示す() {
        MetricResult result = evaluate(Map.of("skipped-tests-max", 2),
                List.of(report("backend", 10, 0, 0, 3, 0)), Map.of()).getFirst();

        assertThat(result.threshold()).isEqualTo(Threshold.atMost(2));
        assertThat(result.threshold().isIncrease()).isFalse();
    }

    @Test
    void コンポーネントごとに件数を数える() {
        List<MetricResult> results = evaluate(Map.of("skipped-tests-max", 1), List.of(
                        report("backend", 10, 0, 0, 1, 0),
                        report("frontend", 10, 0, 0, 2, 0)),
                Map.of());

        assertThat(results).extracting(MetricResult::componentName, MetricResult::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("backend", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("frontend", MeasurementStatus.FAIL));
    }

    @Test
    void スキップしたテストを違反として持つ() {
        RawFinding skipped = new RawFinding("M-10", "skipped", Severity.INFO,
                "A.a はスキップされました", null, null, "backend", "A#a", Map.of());
        EvaluationContext context = new EvaluationContext(run(),
                thresholdsWith(Map.of()),
                input(List.of(report("backend", 1, 0, 0, 1, 0)),
                        List.of(new IdentifiedFinding("fp-a", skipped)), Set.of("M-10")),
                Map.of(), false);

        assertThat(evaluator.evaluate(context).getFirst().findingsToPersist()).hasSize(1);
    }

    private List<MetricResult> evaluate(Map<String, Object> config, List<RawMeasurement> measurements,
                                        Map<String, BigDecimal> previous) {
        EvaluationContext context = new EvaluationContext(run(),
                thresholdsWith(config),
                input(measurements, List.of(), Set.of("M-09", "M-10")),
                previous, !previous.isEmpty());
        return evaluator.evaluate(context);
    }
}
