package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawFinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static com.qualitygate.evaluate.EvaluatorTestSupport.run;
import static com.qualitygate.evaluate.EvaluatorTestSupport.thresholdsWith;
import static org.assertj.core.api.Assertions.assertThat;

class SecretEvaluatorTest {

    private final SecretEvaluator evaluator = new SecretEvaluator();

    @Test
    void 検出が無ければ合格() {
        MetricResult result = evaluate(Map.of(), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isEqualByComparingTo("0");
    }

    @Test
    void 一件でも検出すれば不合格で種類を内訳に残す() {
        MetricResult result = evaluate(Map.of(), List.of(
                secret("aws-access-key-id", "config.py"), secret("github-pat", "ci.env")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.value()).isEqualByComparingTo("2");
        assertThat(result.reason()).contains("2 件").contains("失効");
        assertThat(result.detail().get("rules")).asString().contains("aws-access-key-id", "github-pat");
        assertThat(result.findingsToPersist()).hasSize(2);
    }

    @Test
    void 上限までは合格() {
        MetricResult result = evaluate(Map.of("secrets-max", 1), List.of(secret("jwt", "a.ts")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
    }

    @Test
    void 除外で検出の消えたファイルがあれば理由と内訳に残す() {
        // 誤検出を外すつもりの除外でも、同じファイルの本物の鍵まで数えなくなる。「検出されませんでした」だけでは誤解を招く
        EvaluationContext context = new EvaluationContext(run(), thresholdsWith(Map.of()),
                new com.qualitygate.domain.report.NormalizedInput(List.of(), List.of(), Set.of("M-11"), Map.of(),
                        Map.of("M-11", Set.of("config.py", "ci.env"))), Map.of(), false);

        MetricResult result = evaluator.evaluate(context).getFirst();

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.reason()).isEqualTo(
                "シークレットは検出されませんでした（QG_EXCLUSIONS で検査から外した 2 ファイルに検出があり、件数に含めていません）");
        assertThat(result.detail()).containsEntry("excludedFiles", 2);
    }

    @Test
    void 除外したファイルが無ければ理由に書き添えない() {
        MetricResult result = evaluate(Map.of(), List.of());

        assertThat(result.reason()).isEqualTo("シークレットは検出されませんでした");
        assertThat(result.detail()).containsEntry("excludedFiles", 0);
    }

    private MetricResult evaluate(Map<String, Object> config, List<IdentifiedFinding> findings) {
        EvaluationContext context = new EvaluationContext(run(), thresholdsWith(config),
                input(List.of(), findings, Set.of("M-11")), Map.of(), false);
        List<MetricResult> results = evaluator.evaluate(context);
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    private static IdentifiedFinding secret(String rule, String path) {
        RawFinding finding = new RawFinding("M-11", rule, Severity.CRITICAL, rule, path, 1, null,
                rule + "|" + path, Map.of("tool", "trivy"));
        return new IdentifiedFinding("fp-" + rule, finding);
    }
}
