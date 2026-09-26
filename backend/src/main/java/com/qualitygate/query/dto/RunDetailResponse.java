package com.qualitygate.query.dto;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Run 詳細（S-03）。
 *
 * <p>指標をカテゴリでまとめて返すのは、画面が 6 カテゴリの表として描かれるため
 * （docs/initial/07-api-design.md 4.2）。平坦な配列を返して画面側で分類すると、
 * カテゴリの定義がサーバとクライアントの 2 箇所に存在することになる。
 *
 * <h2>必須と null の宣言について</h2>
 *
 * <p>{@code @NotNull} は入力検証のためではなく、<strong>必ず返す項目を仕様に
 * 明示するため</strong>に付けている。springdoc の既定では必須項目が宣言されず、
 * 生成されるクライアント型は全項目が省略可能になる。そうなると、常に埋まっている
 * 項目にも存在確認が要り、<strong>本当に null になりうる項目（未計測の値など）と
 * 区別がつかなくなる</strong>。
 *
 * <p>逆に null を返しうる項目には {@code @Schema(nullable = true)} を付ける。
 * この API では「null は未計測」「0 は計測して 0 だった」を厳密に分けており、
 * その区別は仕様として宣言されていなければクライアントに伝わらない。
 */
@Schema(description = "Run 1 件の判定結果")
public record RunDetailResponse(
        @NotNull UUID runId,
        @NotNull RepositoryRef repository,
        @NotNull String commitSha,
        @NotNull
        @Schema(nullable = true, description = "コミットへのリンク。URL の組み立てはサーバが持つ")
        String commitUrl,
        @NotNull @Schema(nullable = true) String baseCommitSha,
        @NotNull
        @Schema(nullable = true, description = "差分算出に使った比較対象 Run。初回 Run では null")
        UUID baselineRunId,
        @NotNull String branch,
        @NotNull @Schema(nullable = true) Integer pullRequestNumber,
        @NotNull int attempt,
        @NotNull RunStatus status,
        @NotNull
        @Schema(nullable = true, description = "判定結果。判定前・処理失敗では null")
        Verdict verdict,
        @NotNull @Schema(nullable = true) Completeness completeness,
        @NotNull Instant measuredAt,
        @NotNull @Schema(nullable = true) Instant evaluatedAt,
        @NotNull @Schema(nullable = true) String ciRunUrl,
        @NotNull
        @Schema(nullable = true,
                description = "処理そのものが失敗した場合のみ。判定結果 FAIL とは別物")
        RunFailure failure,
        @NotNull
        @Schema(nullable = true,
                description = "判定に使った設定版。既定値で判定した場合は null")
        GateConfigRef gateConfig,
        @NotNull List<RunCategory> categories,
        @NotNull RunFindingSummary findingSummary,
        @NotNull List<SkippedMetricView> skippedMetrics,
        @NotNull int artifactCount) {

    public record RepositoryRef(@NotNull UUID repositoryId,
                                @NotNull @Schema(nullable = true) String fullName) {
    }

    @Schema(description = "判定に使った設定版。しきい値を変えても過去の Run は当時の判定のまま")
    public record GateConfigRef(@NotNull UUID gateConfigId,
                                @NotNull @Schema(nullable = true) Integer version,
                                @NotNull @Schema(nullable = true) String sourceType,
                                @NotNull @Schema(nullable = true) String sourceCommitSha) {
    }

    @Schema(description = "処理失敗の内容。errorCode で分岐し、hint で対処を示す")
    public record RunFailure(@NotNull @Schema(nullable = true) String errorCode,
                             @NotNull String title,
                             @NotNull @Schema(nullable = true) String detail,
                             @NotNull String hint) {
    }

    public record RunCategory(
            @NotNull @Schema(description = "カテゴリ名（機能テスト など）") String category,
            @NotNull @Schema(description = "カテゴリ内で最も重いステータス") MeasurementStatus status,
            @NotNull
            @Schema(description = "不合格・注意を含むカテゴリは画面の初期状態で展開する")
            boolean expandByDefault,
            @NotNull List<RunMetric> metrics) {
    }

    public record RunMetric(
            @NotNull String metricId,
            @NotNull String name,
            @NotNull
            @Schema(nullable = true,
                    description = "コンポーネント名（backend / frontend）。全体値の指標では null")
            String componentName,
            @NotNull
            @Schema(nullable = true,
                    description = "計測条件（M-02 の実行範囲 changed / all など）。"
                            + "前回値は条件の一致する Run の値だけを使う。条件の区別が無い指標では null")
            String variant,
            @NotNull
            @Schema(nullable = true, description = "計測条件の表示名（変更範囲 / 全量 など）")
            String variantLabel,
            @NotNull MeasurementStatus status,
            @NotNull
            @Schema(nullable = true,
                    description = "実測値。未計測は null。0 は「計測して 0 だった」を意味し別物")
            BigDecimal value,
            @NotNull @Schema(nullable = true) String unit,
            @NotNull
            @Schema(nullable = true,
                    description = "合格ライン。例: {\"operator\": \">=\", \"value\": 75}")
            Map<String, Object> threshold,
            @NotNull @Schema(nullable = true) BigDecimal previousValue,
            @NotNull
            @Schema(nullable = true, description = "前回比。前回値か今回値が無ければ null")
            BigDecimal delta,
            @NotNull
            @Schema(nullable = true, description = "差分が良い方向か。null は前回比なし")
            Boolean deltaImproved,
            @NotNull
            @Schema(nullable = true,
                    description = "判定理由。文言はサーバが持ち、画面はそのまま表示する")
            String reason,
            @NotNull @Schema(nullable = true) Map<String, Object> detail,
            @NotNull @Schema(description = "この指標に紐づく未解消の違反件数")
            long findingCount) {
    }

    @Schema(description = "違反の内訳。解消（RESOLVED）も件数に含めて「直った数」を示す")
    public record RunFindingSummary(@NotNull long newCount, @NotNull long continuing,
                                    @NotNull long resolved, @NotNull long initial,
                                    @NotNull long waived) {
    }

    @Schema(description = "CI からのスキップ申告。受理されなかった申告も残す")
    public record SkippedMetricView(
            @NotNull String metricId,
            @NotNull String name,
            @NotNull String reason,
            @NotNull
            @Schema(description = "設定で許容されているか。false なら当該指標は ERROR")
            boolean accepted) {
    }
}
