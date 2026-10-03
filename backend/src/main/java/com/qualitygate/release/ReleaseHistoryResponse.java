package com.qualitygate.release;

import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** 判定の履歴。新しい順。 */
public record ReleaseHistoryResponse(@NotNull List<ReleaseHistoryItem> items) {

    public record ReleaseHistoryItem(
            @NotNull Instant measuredAt,
            @NotNull String commitSha,
            @NotNull @Schema(description = "計測したコミットを指すタグ") List<String> tags,
            @NotNull Verdict verdict,
            @NotNull @Schema(description = "リリース判定を開くときの指定（タグがあればタグ、無ければコミット SHA）") String ref) {
    }
}
