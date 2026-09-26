package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static com.qualitygate.evaluate.EvaluatorTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/** 参考値の指標（M-15 / M-16 / M-17）の評価器と、参考値の指標の集約での扱い。 */
class ReferenceEvaluatorsTest {

    @Test
    void 重複率はコンポーネントごとに行数で合算し常に参考値() {
        List<MetricResult> results = new DuplicationEvaluator().evaluate(context(List.of(
                RawMeasurement.of("M-15", "backend", null, "percent", Map.of("lines", 1000L, "duplicatedLines", 50L, "clones", 5L)),
                RawMeasurement.of("M-15", "backend", null, "percent", Map.of("lines", 1000L, "duplicatedLines", 0L, "clones", 0L)),
                RawMeasurement.of("M-15", "frontend", null, "percent", Map.of("lines", 0L, "duplicatedLines", 0L)))));

        assertThat(results).extracting(MetricResult::componentName).containsExactly("backend", "frontend");
        assertThat(results).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(MeasurementStatus.REFERENCE);
            assertThat(r.threshold()).isEmpty();
        });
        // 50 / 2000 = 2.5%。割合（5% と 0%）の平均ではない
        assertThat(results.getFirst().value()).isEqualByComparingTo("2.5");
        // 0 行は 0% ではなく値なし
        assertThat(results.get(1).value()).isNull();
    }

    @Test
    void Lighthouseは画面ごとの中央値のうち最も低い画面の値() {
        List<MetricResult> results = new LighthouseEvaluator().evaluate(context(List.of(
                lighthouse("/", 90, 1200), lighthouse("/", 60, 3000), lighthouse("/", 88, 1300),
                lighthouse("/runs/:id", 75, 2000))));

        MetricResult result = results.getFirst();
        assertThat(result.status()).isEqualTo(MeasurementStatus.REFERENCE);
        // / の中央値は 88（揺れた 60 は採らない）。/runs/:id の 75 が最も低い
        assertThat(result.value()).isEqualByComparingTo("75");
        assertThat(result.detail()).containsEntry("worstPage", "/runs/:id");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> pages = (Map<String, Map<String, Object>>) result.detail().get("pages");
        assertThat((BigDecimal) pages.get("/").get("performance")).isEqualByComparingTo("88");
        assertThat((BigDecimal) pages.get("/").get("lcpMs")).isEqualByComparingTo("1300");
        assertThat(pages.get("/")).containsEntry("runs", 3);
    }

    @Test
    void バンドルサイズは前回比を理由に添える() {
        List<MetricResult> results = new BundleSizeEvaluator().evaluate(new EvaluationContext(run(),
                GateThresholds.defaults(),
                input(List.of(RawMeasurement.of("M-17", "frontend", new BigDecimal("110.0"), "KB",
                        Map.of("jsGzipBytes", 102400L, "cssGzipBytes", 10240L))), List.of(), List.of(), Set.of("M-17")),
                Map.of(EvaluationContext.key("M-17", "frontend"), new BigDecimal("100.0")), true));

        assertThat(results.getFirst().status()).isEqualTo(MeasurementStatus.REFERENCE);
        assertThat(results.getFirst().reason()).contains("JavaScript 100.0 KB").contains("前回比 +10.0%");
    }

    @Test
    void 参考値の指標は計測できなくても合否と部分計測とカテゴリの状態に影響しない() {
        List<MetricResult> results = List.of(
                MetricResult.of("M-07", null, MeasurementStatus.PASS, BigDecimal.ZERO, "count", Map.of(), "", Map.of(), List.of()),
                MetricResult.error("M-15", "成果物が提出されていません"),
                MetricResult.of("M-17", "frontend", MeasurementStatus.REFERENCE, BigDecimal.TEN, "KB", Map.of(), "", Map.of(), List.of()));

        assertThat(RunEvaluationService.aggregate(results)).isEqualTo(Verdict.PASS);
        assertThat(RunEvaluationService.completenessOf(results))
                .isEqualTo(com.qualitygate.domain.model.Completeness.FULL);
        // コード構造は M-07 の状態のまま。参考値の指標だけの性能カテゴリは参考値と示す
        assertThat(RunEvaluationService.categoryStatusOf(results))
                .containsEntry("コード構造", "PASS")
                .containsEntry("性能テスト", "REFERENCE");
    }

    private static EvaluationContext context(List<RawMeasurement> measurements) {
        return context(measurements, Map.of());
    }

    private static EvaluationContext context(List<RawMeasurement> measurements, Map<String, BigDecimal> previous) {
        return new EvaluationContext(run(), GateThresholds.defaults(),
                input(measurements, List.of(), List.of(), Set.of("M-15", "M-16", "M-17")), previous, false);
    }

    private static RawMeasurement lighthouse(String page, int performance, int lcp) {
        return RawMeasurement.of("M-16", "frontend", BigDecimal.valueOf(performance), "score",
                Map.of("page", page, "performance", BigDecimal.valueOf(performance), "lcpMs", BigDecimal.valueOf(lcp)));
    }
}
