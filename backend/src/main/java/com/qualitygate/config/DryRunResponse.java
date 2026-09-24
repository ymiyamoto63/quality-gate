package com.qualitygate.config;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 設定変更のドライラン（FR-02-5）の結果。過去の Run を新しい設定で判定し直した場合の試算。 */
@Schema(description = "設定変更のドライラン（FR-02-5）")
public record DryRunResponse(
        @NotNull @Schema(description = "試算した Run の数") int evaluated,
        @NotNull @Schema(description = "判定結果が変わる Run の数") int verdictChanged,
        @NotNull @Schema(description = "合格 → 不合格に変わる Run の数") int newlyFailing,
        @NotNull @Schema(description = "不合格 → 合格に変わる Run の数") int newlyPassing,
        @NotNull List<RunResult> runs,
        @NotNull @Schema(description = "成果物が保持期間で削除されているなど、試算できなかった Run") List<SkippedRun> skipped) {

    public record RunResult(
            @NotNull UUID runId,
            @NotNull Instant measuredAt,
            @NotNull String commitSha,
            @NotNull Verdict currentVerdict,
            @NotNull Verdict simulatedVerdict,
            @NotNull @Schema(description = "判定（合否または指標の状態）が変わる指標だけを並べる") List<MetricChange> changes) {
    }

    public record MetricChange(
            @NotNull String metricId,
            @NotNull @Schema(nullable = true) String componentName,
            @NotNull @Schema(nullable = true, description = "現在の状態。現在は判定されていない指標なら null") MeasurementStatus currentStatus,
            @NotNull MeasurementStatus simulatedStatus,
            @NotNull @Schema(nullable = true) BigDecimal value,
            @NotNull @Schema(nullable = true) String unit,
            @NotNull @Schema(nullable = true) String reason) {
    }

    public record SkippedRun(@NotNull UUID runId, @NotNull String reason) {
    }

    /** ドライランの依頼。 */
    public record Request(
            @NotBlank @Schema(description = "試す .quality-gate.yml の内容") String rawYaml,
            @Min(1) @Max(30) @Schema(nullable = true, description = "試算する直近の Run の数（既定 10、最大 30）")
            Integer runs) {
    }
}
