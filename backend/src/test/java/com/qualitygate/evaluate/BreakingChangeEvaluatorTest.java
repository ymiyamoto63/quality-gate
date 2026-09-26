package com.qualitygate.evaluate;

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
import static org.assertj.core.api.Assertions.assertThat;

class BreakingChangeEvaluatorTest {

    private final BreakingChangeEvaluator evaluator = new BreakingChangeEvaluator();

    @Test
    void 破壊的変更が無ければ合格() {
        MetricResult result = evaluate(0, List.of(report(false)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isEqualByComparingTo("0");
        assertThat(result.threshold()).containsEntry("operator", "<=").containsEntry("value", 0);
    }

    @Test
    void 破壊的変更が1件でもあれば不合格() {
        MetricResult result = evaluate(0, List.of(report(false)), List.of(
                change("api-path-removed-without-deprecation", Severity.HIGH)));

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.value()).isEqualByComparingTo("1");
        assertThat(result.reason()).contains("/v1 → /v2");
        assertThat(result.findingsToPersist()).hasSize(1);
    }

    @Test
    void 破壊的になりうる変更は注意() {
        MetricResult result = evaluate(0, List.of(report(false)), List.of(
                change("response-property-enum-value-added", Severity.MEDIUM)));

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.value()).isEqualByComparingTo("0");
    }

    @Test
    void 非破壊的な変更は数えず違反としても残さない() {
        MetricResult result = evaluate(0, List.of(report(false)), List.of(
                change("endpoint-added", Severity.INFO)));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.detail()).containsEntry("informational", 1L);
        assertThat(result.findingsToPersist()).isEmpty();
    }

    @Test
    void 比較元に定義が無ければ対象外() {
        MetricResult result = evaluate(0, List.of(report(true)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.NOT_APPLICABLE);
        assertThat(result.value()).isNull();
        assertThat(result.reason()).contains("新規 API");
    }

    @Test
    void 比較元に定義が無いと申告されても破壊的変更が報告されていれば判定する() {
        MetricResult result = evaluate(0, List.of(report(true)), List.of(
                change("api-path-removed-without-deprecation", Severity.HIGH)));

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
    }

    @Test
    void 設定で許容した件数までは合格() {
        MetricResult result = evaluate(1, List.of(report(false)), List.of(
                change("api-path-removed-without-deprecation", Severity.HIGH)));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.reason()).contains("合格ライン 1 件以内");
    }

    private MetricResult evaluate(int maximum, List<RawMeasurement> measurements,
                                  List<IdentifiedFinding> findings) {
        EvaluationContext context = new EvaluationContext(run(),
                thresholdsWith("api_contract", Map.of("breaking_changes", maximum)),
                input(measurements, findings, List.of(), Set.of("M-08")), Map.of(), false);
        List<MetricResult> results = evaluator.evaluate(context);
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    private static RawMeasurement report(boolean baseMissing) {
        return RawMeasurement.of("M-08", null, BigDecimal.ZERO, "count",
                Map.of("baseSpecMissing", baseMissing));
    }

    private static IdentifiedFinding change(String id, Severity severity) {
        RawFinding finding = new RawFinding("M-08", id, severity, "GET /api/v1/runs: " + id,
                null, null, null, id, Map.of());
        return new IdentifiedFinding("fp-" + id, finding);
    }
}
