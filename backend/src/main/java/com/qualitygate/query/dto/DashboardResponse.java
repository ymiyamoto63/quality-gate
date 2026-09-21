package com.qualitygate.query.dto;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "ダッシュボード。repository_summaries を読むだけで応答する。")
public record DashboardResponse(
        List<RepositoryCard> repositories,
        @Schema(description = "計測途絶などの警告。画面側で判定させず、サーバが返す")
        List<Alert> alerts) {

    public record RepositoryCard(
            UUID repositoryId,
            String fullName,
            LatestRun latestRun,
            int openCriticalCount,
            int openHighCount,
            int activeWaiverCount,
            Freshness freshness) {
    }

    public record LatestRun(
            UUID runId,
            @Schema(description = "判定結果。未判定なら null")
            Verdict verdict,
            Completeness completeness,
            Instant measuredAt) {
    }

    @Schema(description = "データの鮮度。基準日数は設定値のため、判定結果をサーバが返す。")
    public record Freshness(
            Instant lastMeasuredAt,
            Instant lastFullMeasuredAt,
            boolean staleMeasurement,
            boolean staleFullMeasurement) {
    }

    public record Alert(String code, UUID repositoryId, String message) {
    }
}
