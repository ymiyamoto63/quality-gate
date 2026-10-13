package com.qualitygate.release;

import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 判定の履歴。新しい順。
 *
 * <p>合否だけでなく判定の範囲（判定した件数・対象外の件数・緩めた合格ライン・除外したファイル）を添える。
 * 「10 件を外した合格」と「12 件すべてでの合格」を一覧で見分けられるようにするため（DD-28）。
 */
public record ReleaseHistoryResponse(@NotNull List<ReleaseHistoryItem> items) {

    public record ReleaseHistoryItem(
            @NotNull Instant measuredAt,
            @NotNull String commitSha,
            @NotNull @Schema(description = "計測したコミットを指すタグ") List<String> tags,
            @NotNull Verdict verdict,
            @NotNull @Schema(description = "リリース判定を開くときの指定（タグがあればタグ、無ければコミット SHA）") String ref,
            @NotNull @Schema(description = "合否に使った結果の件数（リリース判定の counts.judged と同じ数え方）") int judgedCount,
            @NotNull @Schema(description = "判定の対象外にした件数（設定で判定から外した指標と、対象外の結果。リリース判定の notJudged の件数）")
            int notJudgedCount,
            @NotNull @Schema(description = "既定より緩い合格ラインで判定した結果があるか") boolean thresholdRelaxed,
            @NotNull @Schema(description = "QG_EXCLUSIONS で計測から外したファイルのある結果があるか"
                    + "（ファイル数は指標ごとに数えるため、合計せずに有無だけを出す）") boolean filesExcluded) {
    }
}
