package com.qualitygate.domain.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 負荷試験 1 回分の結果と、M-03 / M-04 / M-05 の計算式（docs/initial/02-metrics-spec.md M-03）。
 *
 * <p>式をここに 1 つだけ置く。アダプタ（1 回分の値）と評価器（3 回の中央値）が
 * 別々に式を持つと、画面の値と判定が食い違う。
 *
 * @param p95Ms           全リクエストの応答時間 p95（ms）
 * @param requestRate     到達率（全リクエスト数 / 秒）
 * @param requests        全リクエスト数
 * @param failedRequests  失敗したリクエスト数
 * @param scenarios       シナリオ名 → p95（ms）
 * @param environmentName 計測環境の名前。トレンドの系列を分ける軸
 * @param environment     計測環境の内訳（runner / cpu / memory / datasetProfile など）
 */
public record PerformanceSample(
        BigDecimal p95Ms,
        BigDecimal requestRate,
        long requests,
        long failedRequests,
        Map<String, BigDecimal> scenarios,
        String environmentName,
        Map<String, Object> environment) {

    /** 成功したリクエストのスループット（req/s）。 */
    public BigDecimal successRate() {
        if (requests == 0) {
            return BigDecimal.ZERO;
        }
        return requestRate.multiply(BigDecimal.valueOf(requests - failedRequests))
                .divide(BigDecimal.valueOf(requests), 4, RoundingMode.HALF_UP);
    }

    /** {@code 失敗 / 全リクエスト × 100}。 */
    public BigDecimal errorRatePercent() {
        if (requests == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(failedRequests * 100L)
                .divide(BigDecimal.valueOf(requests), 4, RoundingMode.HALF_UP);
    }

    /** 測定値の内訳（{@code detail}）として保存する形。 */
    public Map<String, Object> toDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("p95Ms", p95Ms);
        detail.put("requestRate", requestRate);
        detail.put("requests", requests);
        detail.put("failedRequests", failedRequests);
        detail.put("scenarios", scenarios);
        detail.put("environment", environment);
        return detail;
    }

    /**
     * 中央値。偶数個なら中央 2 つの平均。
     *
     * <p>平均ではなく中央値を採るのは、3 回のうち 1 回だけ外れた値に引きずられないため。
     */
    public static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle))
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    /**
     * 変動係数（標準偏差 / 平均 × 100、%）。2 件未満や平均 0 なら 0。
     * 3 回の値のばらつきから、計測環境が安定していたかを見る。
     */
    public static BigDecimal coefficientOfVariation(List<BigDecimal> values) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        double mean = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        if (mean == 0) {
            return BigDecimal.ZERO;
        }
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2))
                .sum() / values.size();
        return BigDecimal.valueOf(Math.sqrt(variance) / mean * 100)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
