package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.context;
import static com.qualitygate.evaluate.EvaluatorTestSupport.coverage;
import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;

class BranchCoverageEvaluatorTest {

    private final BranchCoverageEvaluator evaluator = new BranchCoverageEvaluator();

    @Test
    void しきい値以上なら合格() {
        List<MetricResult> results = evaluator.evaluate(context(
                input(List.of(coverage("backend", "82.4")), List.of(), List.of(), Set.of("M-01"))));

        assertThat(results).singleElement()
                .satisfies(r -> {
                    assertThat(r.status()).isEqualTo(MeasurementStatus.PASS);
                    assertThat(r.value()).isEqualByComparingTo("82.40");
                    assertThat(r.reason()).contains("75% を満たしています");
                });
    }

    @Test
    void しきい値未満なら不合格() {
        List<MetricResult> results = evaluator.evaluate(context(
                input(List.of(coverage("frontend", "10.61")), List.of(), List.of(), Set.of("M-01"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(results.getFirst().reason()).contains("75% を下回っています");
    }

    @Test
    void 注意水準を下回ると警告() {
        List<MetricResult> results = evaluator.evaluate(context(
                input(List.of(coverage("backend", "77")), List.of(), List.of(), Set.of("M-01"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.WARN);
    }

    @Test
    void 合格していても前回より1ポイント以上落ちれば警告() {
        // 下降が続いていることに気づかないまま、しきい値を割る直前まで放置されるのを防ぐ
        List<MetricResult> results = evaluator.evaluate(context(
                input(List.of(coverage("backend", "84")), List.of(), List.of(), Set.of("M-01")),
                Map.of("M-01/backend", new BigDecimal("86.5")), true));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(results.getFirst().reason()).contains("前回より").contains("低下");
    }

    @Test
    void コンポーネントを合算しない() {
        // 片方の高いカバレッジが、もう片方の低さを隠してはならない
        List<MetricResult> results = evaluator.evaluate(context(input(
                List.of(coverage("backend", "95"), coverage("frontend", "10")),
                List.of(), List.of(), Set.of("M-01"))));

        assertThat(results).hasSize(2);
        assertThat(results).extracting(MetricResult::status)
                .containsExactly(MeasurementStatus.PASS, MeasurementStatus.FAIL);
    }

    @Test
    void 分岐が無い場合は100パーセントとせず合格にする() {
        List<MetricResult> results = evaluator.evaluate(context(
                input(List.of(coverage("backend", null)), List.of(), List.of(), Set.of("M-01"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(results.getFirst().value()).isNull();
        assertThat(results.getFirst().reason()).contains("分岐がない");
    }
}
