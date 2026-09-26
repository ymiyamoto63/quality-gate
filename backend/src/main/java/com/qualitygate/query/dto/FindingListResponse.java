package com.qualitygate.query.dto;

import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 違反一覧（S-04）。
 *
 * <p>必須と null の宣言方針は {@link RunDetailResponse} の注記に従う。
 */
@Schema(description = "Run に紐づく違反の一覧")
public record FindingListResponse(
        @NotNull List<FindingItem> items,
        @NotNull
        @Schema(nullable = true, description = "次ページのカーソル。最終ページでは null")
        String nextCursor,
        @NotNull boolean hasMore,
        @NotNull
        @Schema(description = "絞り込み後の総件数。件数表示と「全部見た」の判断に使う")
        long totalCount) {

    public record FindingItem(
            @NotNull UUID findingId,
            @NotNull String metricId,
            @NotNull String metricName,
            @NotNull @Schema(description = "Run をまたいで違反を同定するキー")
            String fingerprint,
            @NotNull FindingState state,
            @NotNull Severity severity,
            @NotNull @Schema(nullable = true) String ruleId,
            @NotNull String title,
            @NotNull @Schema(nullable = true) String filePath,
            @NotNull @Schema(nullable = true) Integer line,
            @NotNull @Schema(nullable = true) String componentName,
            @NotNull
            @Schema(nullable = true,
                    description = "該当箇所へのリンク。ホスティング先の URL 形式はサーバが持つ。"
                            + "パスを持たない違反（依存パッケージの脆弱性など）では null")
            String sourceUrl,
            @NotNull @Schema(description = "ツール固有の付加情報。無ければ空オブジェクト")
            Map<String, Object> detail) {
    }
}
