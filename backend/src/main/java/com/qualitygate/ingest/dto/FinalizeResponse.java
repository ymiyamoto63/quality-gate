package com.qualitygate.ingest.dto;

import com.qualitygate.domain.model.RunStatus;

import java.util.UUID;

public record FinalizeResponse(UUID runId, RunStatus status, String detailUrl) {
}
