package com.qualitygate.evaluate;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.PerformanceSample;
import com.qualitygate.domain.report.RawMeasurement;
import com.qualitygate.platform.id.Uuid7;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static com.qualitygate.evaluate.EvaluatorTestSupport.thresholdsWith;
import static org.assertj.core.api.Assertions.assertThat;

class PerformanceEvaluatorTest {

    private static final Map<String, Object> PERFORMANCE = Map.of(
            "p95_ms", 500, "arrival_rate_rps", 50, "error_rate_pct", 0.1,
            "scenarios", List.of("dashboard"));

    @Test
    void 三回の中央値で判定する() {
        MetricResult result = p95(sample("300", 50, 0, "300"), sample("350", 50, 0, "300"),
                sample("900", 50, 0, "300"));

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);  // 変動が大きい
        assertThat(result.value()).isEqualByComparingTo("350");
        assertThat(result.variant()).isEqualTo("perf-staging");
        assertThat(result.reason()).contains("変動係数");
    }

    @Test
    void 安定して合格ライン以内なら合格() {
        MetricResult result = p95(sample("300", 50, 0, "250"), sample("310", 50, 0, "250"),
                sample("305", 50, 0, "250"));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isEqualByComparingTo("305");
        assertThat(result.threshold()).containsEntry("operator", "<=");
    }

    @Test
    void シナリオ単位で合格ラインを超えれば不合格() {
        MetricResult result = p95(sample("300", 50, 0, "600"), sample("300", 50, 0, "610"),
                sample("300", 50, 0, "620"));

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.reason()).contains("シナリオ dashboard の p95 610ms");
    }

    @Test
    void 注意水準を超えれば注意() {
        MetricResult result = p95(sample("450", 50, 0, "100"), sample("450", 50, 0, "100"),
                sample("450", 50, 0, "100"));

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("注意水準 400");
    }

    @Test
    void 設定したシナリオが無ければERROR() {
        MetricResult result = p95(sample("300", 50, 0, null));

        assertThat(result.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(result.value()).isNull();
        assertThat(result.reason()).contains("dashboard");
    }

    @Test
    void エラー率が5パーセントを超えればERROR() {
        MetricResult result = p95(
                sample("300", 50, 600, "300"), sample("300", 50, 600, "300"),
                sample("300", 50, 600, "300"));

        assertThat(result.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(result.reason()).contains("5%");
    }

    @Test
    void 実行回数が足りなければ合格ではなく注意() {
        MetricResult result = p95(sample("300", 50, 0, "300"));

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("実行回数が 1 回");
    }

    @Test
    void エラー率は合格ラインの半分を超えると注意() {
        MetricResult pass = evaluate(new ErrorRateEvaluator(), sample("300", 50, 5, "300"), sample("300", 50, 5, "300"),
                sample("300", 50, 5, "300"));
        MetricResult warn = evaluate(new ErrorRateEvaluator(), sample("300", 50, 8, "300"), sample("300", 50, 8, "300"),
                sample("300", 50, 8, "300"));
        MetricResult fail = evaluate(new ErrorRateEvaluator(), sample("300", 50, 20, "300"), sample("300", 50, 20, "300"),
                sample("300", 50, 20, "300"));

        // 10,000 件中 5 件 = 0.05%、8 件 = 0.08%、20 件 = 0.2%
        assertThat(pass.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(warn.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(fail.status()).isEqualTo(MeasurementStatus.FAIL);
    }

    @Test
    void スループットは不合格にせず到達率の95パーセント未満で注意() {
        MetricResult ok = evaluate(new ThroughputEvaluator(), sample("300", 50, 0, "300"), sample("300", 49, 0, "300"),
                sample("300", 50, 0, "300"));
        MetricResult slow = evaluate(new ThroughputEvaluator(), sample("300", 30, 0, "300"), sample("300", 30, 0, "300"),
                sample("300", 30, 0, "300"));

        assertThat(ok.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(slow.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(slow.threshold()).containsEntry("arrivalRateRps", new BigDecimal("50"));
    }

    private static MetricResult p95(PerformanceSample... samples) {
        return evaluate(new ResponseTimeEvaluator(), samples);
    }

    private static MetricResult evaluate(PerformanceEvaluator evaluator, PerformanceSample... samples) {
        List<RawMeasurement> measurements = new ArrayList<>();
        for (PerformanceSample sample : samples) {
            BigDecimal value = switch (evaluator.metricId()) {
                case "M-03" -> sample.p95Ms();
                case "M-04" -> sample.successRate();
                default -> sample.errorRatePercent();
            };
            measurements.add(RawMeasurement.of(evaluator.metricId(), null, value, "ms",
                    sample.toDetail()).withVariant(sample.environmentName()));
        }
        Run run = new Run(Uuid7.generate(), Uuid7.generate(),
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", "main", "ci",
                Instant.parse("2026-09-22T00:00:00Z"), 1);
        EvaluationContext context = new EvaluationContext(run,
                thresholdsWith("performance", PERFORMANCE),
                input(measurements, List.of(), List.of(), Set.of(evaluator.metricId())),
                Map.of(), false);
        List<MetricResult> results = evaluator.evaluate(context);
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    /** 10,000 リクエストの 1 回分。 */
    private static PerformanceSample sample(String p95, int rate, long failed, String dashboard) {
        return new PerformanceSample(new BigDecimal(p95), BigDecimal.valueOf(rate), 10_000,
                failed, dashboard == null ? Map.of() : Map.of("dashboard", new BigDecimal(dashboard)),
                "perf-staging", Map.of("name", "perf-staging"));
    }
}
