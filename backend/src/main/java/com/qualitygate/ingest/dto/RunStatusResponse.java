package com.qualitygate.ingest.dto;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "CI がポーリングするための軽量な状態応答。")
public record RunStatusResponse(
        UUID runId,
        RunStatus status,
        @Schema(description = "判定結果。未判定の場合は null")
        Verdict verdict,
        Completeness completeness,
        String errorCode,
        String detailUrl) {
}
