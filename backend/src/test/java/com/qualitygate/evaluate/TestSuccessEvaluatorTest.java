package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.TestTally;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static com.qualitygate.evaluate.EvaluatorTestSupport.run;
import static com.qualitygate.evaluate.EvaluatorTestSupport.thresholdsWith;
import static org.assertj.core.api.Assertions.assertThat;

class TestSuccessEvaluatorTest {

    private final TestSuccessEvaluator evaluator = new TestSuccessEvaluator();

    @Test
    void すべて成功すれば合格() {
        List<MetricResult> results = evaluate(Map.of(),
                List.of(report("backend", 120, 0, 0, 0, 0)), List.of());

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
            assertThat(result.componentName()).isEqualTo("backend");
            assertThat(result.value()).isEqualByComparingTo("100");
            assertThat(result.reason()).isEqualTo("テスト 120 件がすべて成功しました");
        });
    }

    @Test
    void コンポーネントごとに判定しファイル単位のレポートは合算する() {
        List<MetricResult> results = evaluate(Map.of(), List.of(
                        report("backend", 50, 0, 0, 0, 0),
                        report("backend", 49, 1, 0, 0, 0),
                        report("frontend", 30, 0, 0, 0, 0)),
                List.of(failure("backend", "RunServiceTest", "判定する")));

        assertThat(results).extracting(MetricResult::componentName, MetricResult::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("backend", MeasurementStatus.FAIL),
                        org.assertj.core.groups.Tuple.tuple("frontend", MeasurementStatus.PASS));
        MetricResult backend = results.getFirst();
        assertThat(backend.value()).isEqualByComparingTo("99");
        assertThat(backend.reason()).contains("失敗 1 件・エラー 0 件（実行 100 件中）");
        // 違反は自分のコンポーネントの分だけを持つ
        assertThat(backend.findingsToPersist()).hasSize(1);
        assertThat(results.get(1).findingsToPersist()).isEmpty();
    }

    @Test
    void スキップはM12で判定するため警告にしない() {
        List<MetricResult> results = evaluate(Map.of(),
                List.of(report("backend", 10, 0, 0, 5, 0)), List.of());

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.PASS);
    }

    @Test
    void 再実行で成功したテストがあれば警告() {
        List<MetricResult> results = evaluate(Map.of(),
                List.of(report("backend", 10, 0, 0, 0, 2)), List.of());

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(results.getFirst().reason()).contains("2 件は再実行で成功しました");
    }

    @Test
    void 合格ラインを緩めても失敗があれば警告() {
        List<MetricResult> results = evaluate(Map.of("min_success_rate", 95),
                List.of(report("backend", 99, 1, 0, 0, 0)), List.of());

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.WARN);
    }

    @Test
    void 実行0件は合格ではなくERROR() {
        List<MetricResult> results = evaluate(Map.of(),
                List.of(report("frontend", 0, 0, 0, 4, 0)), List.of());

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(results.getFirst().value()).isNull();
        assertThat(results.getFirst().reason()).contains("4 件はすべてスキップされています");
    }

    private List<MetricResult> evaluate(Map<String, Object> config,
                                        List<RawMeasurement> measurements,
                                        List<IdentifiedFinding> findings) {
        Map<String, Object> values = new HashMap<>(Map.of("min_success_rate", 100, "min_test_count", 1));
        values.putAll(config);
        EvaluationContext context = new EvaluationContext(run(),
                thresholdsWith("test_results", values),
                input(measurements, findings, List.of(), Set.of("M-11", "M-12")), Map.of(), false);
        return evaluator.evaluate(context);
    }

    static RawMeasurement report(String component, long passed, long failed,
                                 long errored, long skipped, long flaky) {
        TestTally tally = new TestTally(passed, failed, errored, skipped, flaky);
        return RawMeasurement.of("M-11", component, tally.successRate(), "percent", tally.toDetail());
    }

    private static IdentifiedFinding failure(String component, String className, String name) {
        RawFinding finding = new RawFinding("M-11", "failed", Severity.HIGH,
                className + "." + name + " が失敗しました", null, null, component,
                className + "#" + name, Map.of("outcome", "failed"));
        return new IdentifiedFinding("fp-" + name, finding);
    }
}
