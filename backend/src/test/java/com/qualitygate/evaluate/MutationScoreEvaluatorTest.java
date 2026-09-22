package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.MutationTally;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.coverage;
import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static com.qualitygate.evaluate.EvaluatorTestSupport.run;
import static com.qualitygate.evaluate.EvaluatorTestSupport.thresholdsWith;
import static org.assertj.core.api.Assertions.assertThat;

class MutationScoreEvaluatorTest {

    private final MutationScoreEvaluator evaluator = new MutationScoreEvaluator();

    @Test
    void しきい値以上なら合格() {
        MetricResult result = single(evaluate(Set.of(), mutation("backend", "changed",
                tally(70, 0, 20, 10, 0))));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isEqualByComparingTo("70.00");
        assertThat(result.variant()).isEqualTo("changed");
        assertThat(result.reason()).contains("60% を満たしています").contains("検出 70 / 対象 100");
        assertThat(result.threshold()).containsEntry("operator", ">=");
    }

    @Test
    void カバーされていないmutationを分母に含めて判定する() {
        // NO_COVERAGE を除くと 60 / 70 = 85.7% で合格に見えてしまう
        MetricResult result = single(evaluate(Set.of(), mutation("backend", "changed",
                tally(60, 0, 10, 30, 0))));

        assertThat(result.value()).isEqualByComparingTo("60.00");
        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
    }

    @Test
    void しきい値未満なら不合格() {
        MetricResult result = single(evaluate(Set.of(), mutation("backend", "changed",
                tally(59, 0, 41, 0, 0))));

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.reason()).contains("60% を下回っています");
    }

    @Test
    void 注意水準を下回ると警告() {
        MetricResult result = single(evaluate(Set.of(), mutation("backend", "changed",
                tally(64, 0, 36, 0, 0))));

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("注意水準 65%");
    }

    @Test
    void TIMED_OUTが1割を超えると過大評価の疑いで警告() {
        // TIMED_OUT は検出側に数えるため、遅いランナーほどスコアが高く出る
        MetricResult result = single(evaluate(Set.of(), mutation("backend", "all",
                tally(70, 20, 10, 0, 0))));

        assertThat(result.value()).isEqualByComparingTo("90.00");
        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("TIMED_OUT").contains("20.00%");
    }

    @Test
    void TIMED_OUTがちょうど1割なら警告しない() {
        MetricResult result = single(evaluate(Set.of(), mutation("backend", "all",
                tally(80, 10, 10, 0, 0))));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
    }

    @Test
    void 生成や実行に失敗したものが1割を超えると計測エラーにし値を出さない() {
        MetricResult result = single(evaluate(Set.of(), mutation("backend", "changed",
                tally(80, 0, 8, 0, 12))));

        assertThat(result.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(result.value()).isNull();
        assertThat(result.reason()).contains("上限 10%").contains("NON_VIABLE 12");
    }

    @Test
    void ミューテーションが0個なら値なしで合格() {
        MetricResult result = single(evaluate(Set.of(), mutation("backend", "changed",
                MutationTally.EMPTY)));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isNull();
        assertThat(result.reason()).contains("生成されませんでした");
    }

    @Test
    void 前回より2ポイント以上落ちれば警告() {
        List<MetricResult> results = evaluate(Set.of(),
                Map.of(EvaluationContext.key("M-02", "backend", "changed"), new BigDecimal("80")),
                mutation("backend", "changed", tally(78, 0, 22, 0, 0)));

        assertThat(single(results).status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(single(results).reason()).contains("前回より 2.00 ポイント低下");
    }

    @Test
    void 実行範囲の違う前回値とは比べない() {
        // 全量の 80% と変更範囲の 70% の差は、品質の変化ではなく範囲の違い
        List<MetricResult> results = evaluate(Set.of(),
                Map.of(EvaluationContext.key("M-02", "backend", "all"), new BigDecimal("80")),
                mutation("backend", "changed", tally(70, 0, 30, 0, 0)));

        assertThat(single(results).status()).isEqualTo(MeasurementStatus.PASS);
    }

    @Test
    void 同じコンポーネントの複数の成果物は件数で合算する() {
        // 割合の平均（(100 + 50) / 2 = 75%）ではなく件数から（(10 + 50) / 110 = 54.5%）
        MetricResult result = single(evaluate(Set.of(),
                mutation("backend", "changed", tally(10, 0, 0, 0, 0)),
                mutation("backend", "changed", tally(50, 0, 50, 0, 0))));

        assertThat(result.value()).isEqualByComparingTo("54.55");
        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.detail()).containsEntry("reports", 2).containsEntry("killed", 60L);
    }

    @Test
    void 実行範囲の違う成果物が混在すれば計測エラー() {
        MetricResult result = single(evaluate(Set.of(),
                mutation("backend", "changed", tally(10, 0, 0, 0, 0)),
                mutation("backend", "all", tally(50, 0, 50, 0, 0))));

        assertThat(result.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(result.reason()).contains("混在");
    }

    @Test
    void 対象外のコンポーネントは測り忘れと区別して対象外として並べる() {
        // frontend は他の指標（M-01）で計測されているが、PIT では測りようがない
        List<MetricResult> results = evaluator.evaluate(context(Set.of("backend"), Map.of(),
                List.of(mutation("backend", "changed", tally(70, 0, 30, 0, 0)),
                        coverage("frontend", "80"))));

        assertThat(results).extracting(MetricResult::componentName)
                .containsExactly("backend", "frontend");
        assertThat(results.get(1).status()).isEqualTo(MeasurementStatus.NOT_APPLICABLE);
        assertThat(results.get(1).reason()).contains("JVM");
        assertThat(results.get(1).value()).isNull();
    }

    @Test
    void 対象コンポーネントの成果物が無ければ計測エラー() {
        List<MetricResult> results = evaluator.evaluate(context(Set.of("backend", "batch"),
                Map.of(), List.of(mutation("backend", "changed", tally(70, 0, 30, 0, 0)))));

        assertThat(results).extracting(MetricResult::componentName)
                .containsExactly("backend", "batch");
        MetricResult batch = results.get(1);
        assertThat(batch.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(batch.reason()).contains("batch の成果物");
    }

    @Test
    void 対象外のコンポーネントから届いた値は判定に使わない() {
        List<MetricResult> results = evaluate(Set.of("backend"),
                mutation("backend", "changed", tally(70, 0, 30, 0, 0)),
                mutation("frontend", "changed", tally(0, 0, 100, 0, 0)));

        MetricResult frontend = results.get(1);
        assertThat(frontend.componentName()).isEqualTo("frontend");
        assertThat(frontend.status()).isEqualTo(MeasurementStatus.NOT_APPLICABLE);
        assertThat(frontend.value()).isNull();
    }

    @Test
    void コンポーネント宣言の無い成果物は全体の値として判定する() {
        List<MetricResult> results = evaluate(Set.of("backend"),
                mutation(null, "all", tally(70, 0, 30, 0, 0)));

        assertThat(single(results).componentName()).isNull();
        assertThat(single(results).status()).isEqualTo(MeasurementStatus.PASS);
    }

    @Test
    void 対象外は部分計測の理由にも不合格の理由にもならない() {
        List<MetricResult> results = List.of(
                MetricResult.of("M-01", "backend", MeasurementStatus.PASS, BigDecimal.TEN,
                        "percent", Map.of(), "", Map.of(), List.of()),
                MetricResult.notApplicable("M-02", "frontend", "対象外"));

        assertThat(RunEvaluationService.aggregate(results))
                .isEqualTo(com.qualitygate.domain.model.Verdict.PASS);
        assertThat(RunEvaluationService.completenessOf(results))
                .isEqualTo(com.qualitygate.domain.model.Completeness.FULL);
        assertThat(RunEvaluationService.categoryStatusOf(results))
                .containsEntry("機能テスト", "PASS");
    }

    private List<MetricResult> evaluate(Set<String> components, RawMeasurement... measurements) {
        return evaluate(components, Map.of(), measurements);
    }

    private List<MetricResult> evaluate(Set<String> components, Map<String, BigDecimal> previous,
                                        RawMeasurement... measurements) {
        return evaluator.evaluate(context(components, previous, List.of(measurements)));
    }

    private static EvaluationContext context(Set<String> components,
                                             Map<String, BigDecimal> previous,
                                             List<RawMeasurement> measurements) {
        GateThresholds thresholds = thresholdsWith("mutation_score",
                Map.of("threshold", 60, "components", List.copyOf(components)));
        NormalizedInput input = input(measurements, List.of(), List.of(), Set.of("M-02"));
        return new EvaluationContext(run(), thresholds, input, previous, !previous.isEmpty());
    }

    private static MetricResult single(List<MetricResult> results) {
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    private static MutationTally tally(long killed, long timedOut, long survived,
                                       long noCoverage, long nonViable) {
        return new MutationTally(killed, timedOut, survived, noCoverage, nonViable, 0, 0, 0);
    }

    private static RawMeasurement mutation(String component, String scope, MutationTally tally) {
        Map<String, Object> detail = new LinkedHashMap<>(tally.toDetail());
        detail.put("mutationScope", scope);
        return RawMeasurement.of("M-02", component, tally.score(), "percent", detail)
                .withVariant(scope);
    }
}
