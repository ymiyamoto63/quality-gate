package com.qualitygate.query.dto;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 品質レポート（FR-08-4）。期間内の既定ブランチの判定を、リポジトリごとにまとめたもの。
 *
 * <p>画面（S-10）はこれを表で描き、ブラウザの印刷で PDF にする。明細は CSV で取り出す。
 */
@Schema(description = "品質レポート（FR-08-4）")
public record ReportResponse(
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        @NotNull @Schema(description = "期間の区切りに使ったタイムゾーン") String zone,
        @NotNull List<RepositoryReport> repositories) {

    @Schema(description = "1 リポジトリ分")
    public record RepositoryReport(
            @NotNull UUID repositoryId,
            @NotNull String fullName,
            @NotNull String defaultBranch,
            @NotNull @Schema(description = "期間内に判定された既定ブランチの Run の数") int runs,
            @NotNull int passed,
            @NotNull int passedWithWarnings,
            @NotNull int failed,
            @NotNull @Schema(nullable = true, description = "合格（警告つきを含む）の割合（%）。Run が無ければ null")
            BigDecimal passRate,
            @NotNull @Schema(nullable = true, description = "期間内で最新の Run") LatestRun latest,
            @NotNull @Schema(description = "最新の Run の指標と、期間の最初の値からの変化") List<MetricRow> metrics) {
    }

    public record LatestRun(
            @NotNull UUID runId,
            @NotNull Instant measuredAt,
            @NotNull String commitSha,
            @NotNull Verdict verdict) {
    }

    public record MetricRow(
            @NotNull String metricId,
            @NotNull String name,
            @NotNull @Schema(nullable = true) String componentName,
            @NotNull @Schema(nullable = true, description = "計測条件（全量 / 変更範囲、計測環境など）") String variant,
            @NotNull MeasurementStatus status,
            @NotNull @Schema(nullable = true) BigDecimal value,
            @NotNull @Schema(nullable = true) String unit,
            @NotNull @Schema(nullable = true, description = "期間内で最初の Run の値（同じコンポーネント・条件）") BigDecimal firstValue,
            @NotNull @Schema(nullable = true, description = "最新の値 − 最初の値") BigDecimal change) {
    }
}
