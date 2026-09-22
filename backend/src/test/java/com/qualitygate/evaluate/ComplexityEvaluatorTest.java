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
    void ベース比較ができない場合は新規関数数を0とする() {
        // 何が新規かを判定できない状態で既存の複雑度超過を計上すると、
        // その変更が大量の問題を持ち込んだという誤った表示になる
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("legacy", 30), function("other", 20)),
                List.of(), Set.of("M-07"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(results.getFirst().value()).isEqualByComparingTo("0");
        assertThat(results.getFirst().reason()).contains("ベース比較ができない");
        // 違反自体は根拠として残す
        assertThat(results.getFirst().findingsToPersist()).hasSize(2);
    }

    @Test
    void ベースに存在しない新規関数の複雑度超過は不合格() {
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("legacy", 30), function("brandNew", 18)),
                List.of(function("legacy", 30)), Set.of("M-07"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(results.getFirst().value()).isEqualByComparingTo("1");
    }

    @Test
    void ベースで既に超過していて悪化していない関数は対象外() {
        // 変更していても複雑度を悪化させていなければ通す
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("legacy", 30)),
                List.of(function("legacy", 30)), Set.of("M-07"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(results.getFirst().value()).isEqualByComparingTo("0");
    }

    @Test
    void ベースより悪化して15を超えたら不合格() {
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("grew", 16)),
                List.of(function("grew", 14)), Set.of("M-07"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(results.getFirst().reason()).contains("新規・悪化した関数が 1 件");
    }

    @Test
    void 注意水準の関数があれば警告() {
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("borderline", 12)),
                List.of(function("borderline", 12)), Set.of("M-07"))));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(results.getFirst().detail()).containsEntry("functionsInWarnBand", 1);
    }

    @Test
    void しきい値以下の関数は保存しない() {
        // アダプタは全関数の CC 値を返す。全件保存すると違反でない行が大量に積まれる。
        List<MetricResult> results = evaluator.evaluate(context(input(List.of(),
                List.of(function("simple", 3), function("alsoSimple", 5)),
                List.of(), Set.of("M-07"))));

        assertThat(results.getFirst().findingsToPersist()).isEmpty();
        assertThat(results.getFirst().detail()).containsEntry("analyzedFunctions", 2);
    }
}
