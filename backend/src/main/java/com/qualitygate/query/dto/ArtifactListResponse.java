package com.qualitygate.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Run に取り込んだ成果物の一覧（FR-07-5）")
public record ArtifactListResponse(@NotNull List<ArtifactItem> items) {

    public record ArtifactItem(
            @NotNull UUID artifactId,
            @NotNull String type,
            @NotNull String filename,
            @NotNull @Schema(nullable = true) String component,
            @NotNull long sizeBytes,
            @NotNull String sha256,
            @NotNull Instant uploadedAt,
            @NotNull @Schema(description = "保持期間を過ぎて実体を削除済みなら true。ダウンロードできない")
            boolean deleted) {
    }
}
