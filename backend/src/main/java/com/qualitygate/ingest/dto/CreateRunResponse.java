package com.qualitygate.ingest.dto;

import com.qualitygate.domain.model.RunStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record CreateRunResponse(
        UUID runId,
        int attempt,
        RunStatus status,
        @Schema(description = "Run 詳細画面への直リンク。CI のログに出して、不合格時にその場から飛べるようにする")
        String detailUrl) {
}
