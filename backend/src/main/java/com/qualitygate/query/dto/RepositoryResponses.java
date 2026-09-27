package com.qualitygate.query.dto;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** リポジトリの参照（S-02 リポジトリ詳細）。 */
public final class RepositoryResponses {

    private RepositoryResponses() {
    }

    public record RepositoryItem(
            @NotNull UUID repositoryId,
            @NotNull String fullName,
            @NotNull String owner,
            @NotNull String name,
            @NotNull String defaultBranch,
            @NotNull Instant createdAt) {
    }

    @Schema(description = "リポジトリ詳細（S-02）。指標の表は latestRunId の Run 詳細から描く")
    public record RepositoryDetail(
            @NotNull RepositoryItem repository,
            @NotNull @Schema(nullable = true, description = "判定済みの最新 Run。まだ無ければ null")
            LatestRunSummary latestRun,
            @NotNull @Schema(nullable = true) UUID lastFullRunId,
            @NotNull RepositoryFreshness freshness) {
    }

    public record LatestRunSummary(
            @NotNull UUID runId,
            @NotNull String commitSha,
            @NotNull String branch,
            @NotNull RunStatus status,
            @NotNull @Schema(nullable = true) Verdict verdict,
            @NotNull @Schema(nullable = true) Completeness completeness,
            @NotNull Instant measuredAt) {
    }

    @Schema(description = "最後の計測と最後の完全計測の日時（FR-06-2 / FR-06-3）")
    public record RepositoryFreshness(
            @NotNull @Schema(nullable = true) Instant lastMeasuredAt,
            @NotNull @Schema(nullable = true) Instant lastFullMeasuredAt) {
    }
}
