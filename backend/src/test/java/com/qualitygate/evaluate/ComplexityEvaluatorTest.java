package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.analyzed;
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
        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.PASS);
    }

    @Test
    void しきい値以下の関数だけなら合格で中間の段階は無い() {
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("borderline", 12)), Set.of("M-06"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(results.getFirst().findingsToPersist()).isEmpty();
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

    @Test
    void コンポーネントごとに判定し片方の違反がもう片方に隠れない() {
        List<MetricResult> results = evaluator.evaluate(context(input(
                List.of(analyzed("backend", 1), analyzed("frontend", 1)),
                List.of(function("backend", "simple", 3), function("frontend", "legacy", 30)),
                Set.of("M-06"))));

        assertThat(results).extracting(MetricResult::componentName, MetricResult::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("backend", MeasurementStatus.PASS),
                        org.assertj.core.groups.Tuple.tuple("frontend", MeasurementStatus.FAIL));
        assertThat(results.get(1).findingsToPersist()).hasSize(1);
    }

    @Test
    void 関数が無くても成果物が届いたコンポーネントは合格の行を出す() {
        // 行が無いと、成果物の届かなかったコンポーネント（ERROR）と区別できない
        List<MetricResult> results = evaluator.evaluate(context(input(
                List.of(analyzed("frontend", 0)), List.of(), Set.of("M-06"))));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.componentName()).isEqualTo("frontend");
            assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
            assertThat(result.detail()).containsEntry("analyzedFunctions", 0);
        });
    }
}
