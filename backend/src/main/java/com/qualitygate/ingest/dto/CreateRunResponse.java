package com.qualitygate.ingest.dto;

import com.qualitygate.domain.model.RunStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record CreateRunResponse(
        UUID runId,
        int attempt,
        RunStatus status,
        @Schema(description = "リリース判定の画面（このコミット）への直リンク。CI のログに出して、その場から飛べるようにする")
        String detailUrl) {
}
