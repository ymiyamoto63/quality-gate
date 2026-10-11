package com.qualitygate.release;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 指標の推移。判定済みの計測（同じコミットは最後の判定だけ）を古い順に並べ、指標ごとの値をその並びに載せる。
 *
 * <p>点は {@code runIndex} で {@code runs} の位置を指す。値の無い計測（その指標を判定していない）は点を持たない。
 */
@Schema(description = "指標の推移。判定の履歴と同じ数え方の計測を、古い順に並べる")
public record MetricTrendResponse(
        @NotNull @Schema(description = "横軸に並べる計測（古い順、最大 50 件）") List<TrendRun> runs,
        @NotNull @Schema(description = "期間内に一度でも判定した指標（指標 ID の順）") List<TrendMetric> metrics) {

    public record TrendRun(
            @NotNull Instant measuredAt,
            @NotNull String commitSha,
            @NotNull @Schema(description = "計測したコミットを指すタグ") List<String> tags,
            @NotNull Verdict verdict,
            @NotNull @Schema(description = "リリース判定を開くときの指定（タグがあればタグ、無ければコミット SHA）") String ref) {
    }

    public record TrendMetric(
            @NotNull String metricId,
            @NotNull String name,
            @NotNull @Schema(description = "何を見る指標か") String summary,
            @NotNull @Schema(nullable = true, description = "値の単位（percent / ms / count）") String unit,
            @NotNull @Schema(nullable = true,
                    description = "縦軸の上端を固定するときの値（% の指標は 100）。null なら値と合格ラインから決める")
            BigDecimal axisMax,
            @NotNull @Schema(description = "コンポーネント・計測条件ごとの線") List<TrendSeries> series) {
    }

    public record TrendSeries(
            @NotNull @Schema(nullable = true) String componentName,
            @NotNull @Schema(nullable = true) String variantLabel,
            @NotNull @Schema(description = "計測の古い順") List<TrendPoint> points) {
    }

    public record TrendPoint(
            @NotNull @Schema(description = "runs の位置") int runIndex,
            @NotNull @Schema(description = "PASS / FAIL / ERROR / NOT_APPLICABLE") MeasurementStatus status,
            @NotNull @Schema(nullable = true, description = "実測値。計測エラー・対象外は null") BigDecimal value,
            @NotNull @Schema(nullable = true,
                    description = "その計測の判定に使った合格ライン（縦軸の値）。前回からの増加で判定する指標は null")
            BigDecimal thresholdValue,
            @NotNull @Schema(nullable = true, description = "合格ラインを表示用にした文字列（≥ 75% など）") String threshold,
            @NotNull @Schema(description = "合格ラインを既定値より緩めて判定したか") boolean thresholdRelaxed,
            @NotNull @Schema(nullable = true,
                    description = "緩めたときの既定の合格ライン（≥ 75% など）。緩めていなければ null")
            String defaultThreshold) {
    }
}
