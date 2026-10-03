package com.qualitygate.release;

import com.qualitygate.domain.model.MeasurementStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * リリース判定。指定したタグ・コミット（指定が無ければ最新の計測）の、全指標の合否と説明。
 *
 * <p>必ず返す項目には {@code @NotNull} を付け、null になりうる項目は {@code nullable} を明示する
 * （画面の型を OpenAPI から生成するため）。
 */
@Schema(description = "リリース判定。判定済みの計測（Run）から組み立てる")
public record ReleaseReportResponse(
        @NotNull String repositoryFullName,
        @NotNull @Schema(nullable = true, description = "判定したコミット。計測が 1 件も無ければ null") String commitSha,
        @NotNull @Schema(nullable = true) String commitUrl,
        @NotNull ReleaseDecision decision,
        @NotNull @Schema(description = "判定の理由（1 文）。文言はサーバが持ち、画面はそのまま表示する")
        String decisionReason,
        @NotNull @Schema(nullable = true, description = "判定に使った計測。未計測なら null") ReleaseRun run,
        @NotNull ReleaseCounts counts,
        @NotNull @Schema(description = "指標ごとの結果。不合格を先に並べる。合否に使わない対象外の結果は含めない")
        List<ReleaseMetric> metrics,
        @NotNull @Schema(description = "結果に現れた指標の説明（指標 ID ごとに 1 件、metrics と同じ並び）")
        List<ReleaseGuide> guides) {

    public record ReleaseRun(
            @NotNull Instant measuredAt,
            @NotNull @Schema(description = "計測したコミットを指すタグ") List<String> tags,
            @NotNull @Schema(nullable = true, description = "計測したワークフローの実行 URL") String ciRunUrl,
            @NotNull @Schema(nullable = true,
                    description = "比較元のコミット。破壊的変更・スキップの増加はここからの差で数える。タグで計測したときは前のタグ")
            String baseCommitSha) {
    }

    @Schema(description = "合否に使った指標の件数")
    public record ReleaseCounts(@NotNull int judged, @NotNull int passed, @NotNull int failed) {
    }

    public record ReleaseMetric(
            @NotNull String metricId,
            @NotNull String name,
            @NotNull @Schema(nullable = true) String componentName,
            @NotNull @Schema(nullable = true) String variantLabel,
            @NotNull @Schema(description = "PASS（合格）/ FAIL（不合格）/ ERROR（計測できなかった。不合格として扱う）")
            MeasurementStatus status,
            @NotNull @Schema(nullable = true, description = "実測値。計測エラーは null") BigDecimal value,
            @NotNull @Schema(nullable = true) String unit,
            @NotNull @Schema(nullable = true, description = "合格ラインを表示用にした文字列（≥ 75% など）") String threshold,
            @NotNull @Schema(nullable = true) String reason,
            @NotNull @Schema(description = "不合格のときの主な違反（深刻な順に最大 10 件）。合格なら空") List<ReleaseFinding> findings,
            @NotNull @Schema(description = "不合格のときの違反の総数。合格なら 0") int findingCount) {
    }

    public record ReleaseFinding(
            @NotNull String title,
            @NotNull @Schema(nullable = true, description = "ファイルと行（backend/src/Foo.java:42 など）") String location,
            @NotNull @Schema(nullable = true, description = "GitHub の該当箇所") String url) {
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
