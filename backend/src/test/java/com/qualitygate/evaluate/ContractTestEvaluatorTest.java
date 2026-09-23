package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.ContractTally;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static com.qualitygate.evaluate.EvaluatorTestSupport.run;
import static com.qualitygate.evaluate.EvaluatorTestSupport.thresholdsWith;
import static org.assertj.core.api.Assertions.assertThat;

class ContractTestEvaluatorTest {

    private final ContractTestEvaluator evaluator = new ContractTestEvaluator();

    @Test
    void すべて成功すれば合格() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 12, 0, 0, 0, 0)),
                List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isEqualByComparingTo("100");
        assertThat(result.reason()).isEqualTo("契約テスト 12 件がすべて成功しました");
        assertThat(result.threshold()).containsEntry("operator", ">=")
                .containsEntry("minTestCount", 1);
    }

    @Test
    void 一件でも失敗すれば不合格() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 9, 1, 0, 0, 0)),
                List.of(failure("RunQueryApiIT", "Run詳細を返す")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.value()).isEqualByComparingTo("90");
        assertThat(result.reason()).contains("失敗 1 件・エラー 0 件（実行 10 件中）");
        assertThat(result.findingsToPersist()).hasSize(1);
    }

    @Test
    void 丸めると100になる失敗も不合格にする() {
        // 999,999 / 1,000,000 は四捨五入すると 100.0000 になる。件数で比べる
        MetricResult result = evaluate(Map.of(),
                List.of(report("backend", 999_999, 1, 0, 0, 0)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.value()).isLessThan(new java.math.BigDecimal("100"));
    }

    @Test
    void 実行0件は合格ではなくERROR() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 0, 0, 0, 3, 0)),
                List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(result.value()).isNull();
        assertThat(result.reason()).contains("1 件も実行されていません")
                .contains("3 件はすべてスキップ");
    }

    @Test
    void 最小実行件数に届かなければERROR() {
        MetricResult result = evaluate(Map.of("min_test_count", 5),
                List.of(report("backend", 3, 0, 0, 0, 0)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(result.reason()).contains("実行件数が 3 件で、最小実行件数 5 件に届きません");
    }

    @Test
    void スキップがあれば注意() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 4, 0, 0, 1, 0)),
                List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.value()).isEqualByComparingTo("100");
        assertThat(result.reason()).contains("1 件がスキップ");
    }

    @Test
    void 再実行で成功したテストがあれば注意() {
        MetricResult result = evaluate(Map.of(), List.of(report("backend", 4, 0, 0, 0, 1)),
                List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("1 件は再実行で成功");
    }

    @Test
    void 合格ラインを緩めても失敗は注意として示す() {
        MetricResult result = evaluate(Map.of("min_success_rate", 90),
                List.of(report("backend", 19, 1, 0, 0, 0)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("1 件失敗");
    }

    @Test
    void consumerとproviderを合算して判定し内訳を残す() {
        // 片側だけ成功しても契約は守られていない
        MetricResult result = evaluate(Map.of(), List.of(
                report("backend", 10, 0, 0, 0, 0),
                report("frontend", 5, 0, 1, 0, 0)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.componentName()).isNull();
        assertThat(result.detail()).containsEntry("executed", 16L);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) result.detail().get("components");
        assertThat(components).containsOnlyKeys("backend", "frontend");
    }

    private MetricResult evaluate(Map<String, Object> config, List<RawMeasurement> measurements,
                                  List<IdentifiedFinding> findings) {
        Map<String, Object> values = new java.util.HashMap<>(Map.of(
                "min_success_rate", 100, "min_test_count", 1, "breaking_changes", 0));
        values.putAll(config);
        EvaluationContext context = new EvaluationContext(run(),
                thresholdsWith("api_contract", values),
                input(measurements, findings, List.of(), Set.of("M-08")), Map.of(), false);
        List<MetricResult> results = evaluator.evaluate(context);
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    private static RawMeasurement report(String component, long passed, long failed,
                                         long errored, long skipped, long flaky) {
        ContractTally tally = new ContractTally(passed, failed, errored, skipped, flaky);
        return RawMeasurement.of("M-08", component, tally.successRate(), "percent",
                tally.toDetail());
    }

    private static IdentifiedFinding failure(String className, String name) {
        RawFinding finding = new RawFinding("M-08", "failed", Severity.HIGH,
                className + "." + name + " が失敗しました", null, null, "backend",
                className + "#" + name, Map.of("outcome", "failed"));
        return new IdentifiedFinding("fp-" + name, finding);
    }
}
