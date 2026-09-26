package com.qualitygate.query.dto;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "ダッシュボード。リポジトリごとの最新の判定済み Run と、最後の完全計測。")
public record DashboardResponse(
        List<RepositoryCard> repositories) {

    public record RepositoryCard(
            UUID repositoryId,
            String fullName,
            LatestRun latestRun,
            int openCriticalCount,
            int openHighCount,
            Freshness freshness) {
    }

    public record LatestRun(
            UUID runId,
            @Schema(description = "判定結果。未判定なら null")
            Verdict verdict,
            Completeness completeness,
            Instant measuredAt) {
    }

    @Schema(description = "最後の計測と最後の完全計測の日時")
    public record Freshness(
            Instant lastMeasuredAt,
            Instant lastFullMeasuredAt) {
    }
}
