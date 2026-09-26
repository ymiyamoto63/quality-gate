package com.qualitygate.query.dto;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Run 一覧。リポジトリ詳細（S-02）の履歴表に使う。
 *
 * <p>必須と null の宣言方針は {@link RunDetailResponse} の注記に従う。
 */
@Schema(description = "Run の一覧。新しいものから返す")
public record RunListResponse(
        @NotNull List<RunSummary> items,
        @NotNull @Schema(nullable = true) String nextCursor,
        @NotNull boolean hasMore) {

    public record RunSummary(
            @NotNull UUID runId,
            @NotNull String commitSha,
            @NotNull String branch,
            @NotNull @Schema(nullable = true) Integer pullRequestNumber,
            @NotNull RunStatus status,
            @NotNull @Schema(nullable = true) Verdict verdict,
            @NotNull @Schema(nullable = true) Completeness completeness,
            @NotNull Instant measuredAt,
            @NotNull @Schema(nullable = true) Instant evaluatedAt) {
    }
}
