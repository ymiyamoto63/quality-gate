package com.qualitygate.ingest.dto;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "確定と判定の結果。収集ランナーはこれをログに出す")
public record FinalizeResponse(
        UUID runId,
        @Schema(description = "EVALUATED（判定済み）か FAILED（処理失敗）") RunStatus status,
        @Schema(description = "判定結果。処理失敗なら null") Verdict verdict,
        @Schema(description = "完全計測か部分計測か。処理失敗なら null") Completeness completeness,
        @Schema(description = "処理失敗の理由のコード。判定済みなら null") String errorCode,
        String detailUrl) {
}
