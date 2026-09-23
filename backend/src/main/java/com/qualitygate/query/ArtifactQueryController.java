package com.qualitygate.query;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.query.dto.ArtifactListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.UUID;

/** 取り込んだ元成果物の一覧とダウンロード（FR-07-5）。 */
@RestController
@RequestMapping("/api/v1/runs/{runId}/artifacts")
@Tag(name = "Runs")
public class ArtifactQueryController {

    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final ArtifactStore artifactStore;

    public ArtifactQueryController(RunRepository runs, ArtifactRecordRepository artifacts,
                                   ArtifactStore artifactStore) {
        this.runs = runs;
        this.artifacts = artifacts;
        this.artifactStore = artifactStore;
    }

    @GetMapping
    @Operation(summary = "Run に取り込んだ成果物を一覧する")
    @Transactional(readOnly = true)
    public ArtifactListResponse list(@PathVariable UUID runId) {
        if (!runs.existsById(runId)) {
            throw ApiException.notFound("Run", runId);
        }
        return new ArtifactListResponse(artifacts.findByRunId(runId).stream()
                .sorted(Comparator.comparing(ArtifactRecord::getUploadedAt))
                .map(a -> new ArtifactListResponse.ArtifactItem(a.getId(), a.getType().wire(),
                        a.getFilename(), a.getComponentName(), a.getSizeBytes(), a.getSha256(),
                        a.getUploadedAt(), a.getDeletedAt() != null))
                .toList());
    }

    @GetMapping("/{artifactId}/content")
    @Operation(summary = "成果物をダウンロードする",
            description = "保持期間を過ぎて実体を削除済みなら 409 ARTIFACTS_DELETED。")
    @Transactional(readOnly = true)
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID runId,
                                                       @PathVariable UUID artifactId) {
        ArtifactRecord artifact = artifacts.findById(artifactId)
                .filter(a -> a.getRunId().equals(runId))
                .orElseThrow(() -> ApiException.notFound("成果物", artifactId));
        if (artifact.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.ARTIFACTS_DELETED,
                    "この成果物は保持期間を過ぎて削除されています（%s）".formatted(artifact.getDeletedAt()));
        }
        // 取り込んだ内容をそのまま返す。ブラウザに解釈させず、必ずダウンロードさせる
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(artifact.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(artifact.getFilename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(artifactStore.open(artifact.getStorageKey())));
    }
}
