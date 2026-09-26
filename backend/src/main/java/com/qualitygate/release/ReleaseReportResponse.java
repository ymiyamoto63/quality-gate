package com.qualitygate.release;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * リリース判定（UC-10 / S-11）。指定したタグ・コミットの時点の、全指標の合否と説明。
 *
 * <p>{@code @NotNull} と {@code nullable} の付け方は RunDetailResponse と同じ（必ず返す項目を仕様に明示する）。
 */
@Schema(description = "リリース判定。指定したタグ・コミットで判定済みの Run から組み立てる")
public record ReleaseReportResponse(
        @NotNull UUID repositoryId,
        @NotNull String repositoryFullName,
        @NotNull @Schema(description = "指定したタグ・コミット（前後の空白を除いたもの）") String ref,
        @NotNull ReleaseRefResolver.RefType refType,
        @NotNull String commitSha,
        @NotNull String commitUrl,
        @NotNull ReleaseDecision decision,
        @NotNull @Schema(description = "判定の理由（1 文）。文言はサーバが持ち、画面はそのまま表示する")
        String decisionReason,
        @NotNull
        @Schema(nullable = true, description = "判定に使った Run。未計測なら null")
        ReleaseRun run,
        @NotNull
        @Schema(description = "同じコミットのほかの Run の件数（判定には使っていない）")
        int otherRunCount,
        @NotNull
        @Schema(nullable = true, description = "判定に使った合格ライン。既定値で判定した場合と未計測では null")
        ReleaseGateConfig gateConfig,
        @NotNull ReleaseCounts counts,
        @NotNull
        @Schema(description = "指標ごとの結果。不合格・計測エラー・注意を先に、参考値を最後に並べる")
        List<ReleaseMetric> metrics,
        @NotNull
        @Schema(description = "結果に現れた指標の説明（指標 ID ごとに 1 件、metrics と同じ並び）")
        List<ReleaseGuide> guides) {

    public record ReleaseRun(@NotNull UUID runId,
                         @NotNull Instant measuredAt,
                         @NotNull String branch,
                         @NotNull int attempt,
                         @NotNull Verdict verdict,
                         @NotNull Completeness completeness,
                         @NotNull @Schema(nullable = true,
                                 description = "比較元のコミット。新規の違反・破壊的変更・スキップの増加はここからの差で数える。"
                                         + "タグで計測したときは前のタグ")
                         String baseCommitSha) {
    }

    @Schema(description = "合格ラインの版。しきい値を変えた理由は、この版のコミットに残る")
    public record ReleaseGateConfig(@NotNull int version,
                                @NotNull String sourceType,
                                @NotNull @Schema(nullable = true) String sourceCommitSha,
                                @NotNull @Schema(description = "計測の対象から外したパス") List<String> exclusions) {
    }

    @Schema(description = "合否に使った指標の件数（参考値・対象外を除く）")
    public record ReleaseCounts(@NotNull int judged, @NotNull int passed, @NotNull int warned,
                         @NotNull int failed, @NotNull int errored, @NotNull int skipped) {
    }

    public record ReleaseMetric(
            @NotNull String metricId,
            @NotNull String name,
            @NotNull String category,
            @NotNull @Schema(nullable = true) String componentName,
            @NotNull @Schema(nullable = true) String variantLabel,
            @NotNull @Schema(nullable = true) String scenario,
            @NotNull MeasurementStatus status,
            @NotNull @Schema(nullable = true, description = "実測値。未計測は null") BigDecimal value,
            @NotNull @Schema(nullable = true) String unit,
            @NotNull
            @Schema(nullable = true, description = "合格ラインを表示用にした文字列（≥ 75% など）")
            String threshold,
            @NotNull @Schema(nullable = true) String reason,
            @NotNull boolean referenceOnly) {
    }

    public record ReleaseGuide(
            @NotNull String metricId,
            @NotNull String name,
            @NotNull @Schema(description = "何を見る指標か") String summary,
            @NotNull String basis,
            @NotNull @Schema(description = "根拠の種類の表示名（外部基準 など）") String basisLabel,
            @NotNull @Schema(description = "既定の合格ラインにした理由") String rationale,
            @NotNull @Schema(description = "不合格のまま出すと何が起きるか") String risk,
            @NotNull @Schema(description = "技術的な定義") String definition,
            @NotNull @Schema(description = "計測に使うライブラリ・ソフトウェア") String tools) {
    }
}
