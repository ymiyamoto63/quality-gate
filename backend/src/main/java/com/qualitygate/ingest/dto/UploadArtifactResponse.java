package com.qualitygate.ingest.dto;

import java.util.UUID;

public record UploadArtifactResponse(UUID artifactId, long sizeBytes, String sha256) {
}
