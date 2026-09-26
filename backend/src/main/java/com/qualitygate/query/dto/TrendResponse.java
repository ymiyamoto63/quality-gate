package com.qualitygate.query.dto;

import com.qualitygate.domain.model.MeasurementStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 指標の時系列（S-05）。
 *
 * <p>必須と null の宣言方針は {@link RunDetailResponse} の注記に従う。
 */
@Schema(description = "指標の時系列。計測環境ごとに系列を分ける")
public record TrendResponse(
        @NotNull String metricId,
        @NotNull String name,
        @NotNull @Schema(nullable = true) String unit,
        @NotNull
        @Schema(nullable = true,
                description = "重畳表示する合格ライン。期間内で最後に適用された値。"
                        + "期間中にしきい値が変わった場合は thresholdChanged が true になる")
        Map<String, Object> threshold,
        @NotNull
        @Schema(description = "期間内でしきい値が変わったか。true のとき、"
                + "1 本の線では過去の判定を説明できない")
        boolean thresholdChanged,
        @NotNull String branch,
        @NotNull Instant from,
        @NotNull Instant to,
        @NotNull List<TrendSeries> series) {

    /**
     * 系列。
     *
     * @param colorIndex 色の割り当て番号。<strong>サーバが固定して返す</strong>。
     *        画面側で「並び順の n 番目」に色を振ると、絞り込みで系列が減ったときに
     *        生き残った系列の色が塗り替わり、同じものが別の色で見える
     */
    public record TrendSeries(
            @NotNull String seriesId,
            @NotNull String label,
            @NotNull @Schema(nullable = true) String componentName,
            @NotNull int colorIndex,
            @NotNull List<TrendPoint> points) {
    }

    public record TrendPoint(
            @NotNull UUID runId,
            @NotNull String commitSha,
            @NotNull Instant measuredAt,
            @NotNull
            @Schema(nullable = true,
                    description = "実測値。未計測は null。0 を返さない。"
                            + "0 を返すと、グラフ上で「極めて良い値」に見えてしまう")
            BigDecimal value,
            @NotNull MeasurementStatus status) {
    }
}
