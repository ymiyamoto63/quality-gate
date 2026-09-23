package com.qualitygate.admin.dto;

import com.qualitygate.domain.entity.IngestToken;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IngestTokenResponses {

    private IngestTokenResponses() {
    }

    @Schema(description = "発行した Ingest Token。token は<strong>この応答でのみ</strong>返す")
    public record IssuedToken(
            @NotNull UUID tokenId,
            @NotNull @Schema(description = "平文のトークン。以後どの API からも取得できない") String token,
            @NotNull String tokenPrefix,
            @NotNull Instant createdAt) {
    }

    @Schema(description = "発行済みのトークン。平文もハッシュも返さない")
    public record TokenSummary(
            @NotNull UUID tokenId,
            @NotNull String tokenPrefix,
            @NotNull @Schema(nullable = true) String description,
            @NotNull Instant createdAt,
            @NotNull @Schema(nullable = true, description = "一度も使われていなければ null") Instant lastUsedAt,
            @NotNull @Schema(nullable = true) Instant revokedAt) {

        public static TokenSummary of(IngestToken token) {
            return new TokenSummary(token.getId(), token.getTokenPrefix(), token.getDescription(),
                    token.getCreatedAt(), token.getLastUsedAt(), token.getRevokedAt());
        }
    }

    public record TokenList(@NotNull List<TokenSummary> items) {
    }
}
