package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.context;
import static com.qualitygate.evaluate.EvaluatorTestSupport.function;
import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;

class ComplexityEvaluatorTest {

    private final ComplexityEvaluator evaluator = new ComplexityEvaluator();

    @Test
    void しきい値を超える関数があれば件数を数えて不合格() {
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("legacy", 30), function("other", 20), function("simple", 3)),
                Set.of("M-06"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(results.getFirst().value()).isEqualByComparingTo("2");
        assertThat(results.getFirst().reason()).contains("15 超の関数が 2 件");
        assertThat(results.getFirst().findingsToPersist()).hasSize(2);
    }

    @Test
    void しきい値ちょうどの関数は合格() {
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("atLimit", 15)), Set.of("M-06"))));

        assertThat(results.getFirst().value()).isEqualByComparingTo("0");
        assertThat(results.getFirst().status()).isNotEqualTo(MeasurementStatus.FAIL);
    }

    @Test
    void 注意水準の関数があれば警告() {
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("borderline", 12)), Set.of("M-06"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(results.getFirst().detail()).containsEntry("functionsInWarnBand", 1);
    }

    @Test
    void しきい値以下の関数は保存しない() {
        // アダプタは全関数の CC 値を返す。全件保存すると違反でない行が大量に積まれる。
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("simple", 3), function("alsoSimple", 5)), Set.of("M-06"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(results.getFirst().findingsToPersist()).isEmpty();
        assertThat(results.getFirst().detail()).containsEntry("analyzedFunctions", 2);
    }
}
