package com.qualitygate.ingest;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.ingest.dto.CreateRunRequest;
import com.qualitygate.ingest.dto.CreateRunResponse;
import com.qualitygate.ingest.dto.FinalizeResponse;
import com.qualitygate.ingest.dto.UploadArtifactResponse;
import com.qualitygate.pipeline.RunEvaluationPipeline;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/**
 * 収集ランナーからの取り込み API。認証は Ingest Token（書き込み専用。収集ランナーの 1 つだけ）。
 *
 * <p>{@code finalize} はその場で判定し、判定結果を返す（DD-15）。
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Ingest", description = "CI からの計測結果の取り込み")
public class IngestController {

    private final IngestService ingestService;
    private final RunEvaluationPipeline pipeline;

    public IngestController(IngestService ingestService, RunEvaluationPipeline pipeline) {
        this.ingestService = ingestService;
        this.pipeline = pipeline;
    }

    @PostMapping
    @Operation(summary = "Run を作成する",
            description = "計測開始時に呼ぶ。同一コミットへの再送信は attempt を増やした新しい Run になる。")
    public ResponseEntity<CreateRunResponse> createRun(@Valid @RequestBody CreateRunRequest request) {
        Run run = ingestService.createRun(request);
        CreateRunResponse body = new CreateRunResponse(
                run.getId(), run.getAttempt(), run.getStatus(), ingestService.detailUrl(run.getId()));
        return ResponseEntity.created(URI.create("/api/v1/runs/" + run.getId())).body(body);
    }

    @PostMapping(path = "/{runId}/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "成果物をアップロードする",
            description = "この時点ではパースしない。受領・検証・保存のみを行い、パースは確定時の判定で行う。")
    public ResponseEntity<UploadArtifactResponse> uploadArtifact(
            @PathVariable UUID runId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam(value = "component", required = false) String component,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "metadata", required = false) String metadata) {

        ArtifactType artifactType = parseType(type);
        try (InputStream content = file.getInputStream()) {
            ArtifactRecord record = ingestService.storeArtifact(runId,
                    artifactType, originalName(file), component, scope, metadata,
                    content, file.getSize());
            return ResponseEntity.accepted().body(new UploadArtifactResponse(
                    record.getId(), record.getSizeBytes(), record.getSha256()));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "アップロードされたファイルを読み取れませんでした: " + e.getMessage());
        }
    }

    @PostMapping("/{runId}/finalize")
    @Operation(summary = "取り込み完了を宣言し、判定する",
            description = "その場で判定し、判定結果を返す。判定に失敗した場合も 200 で、status が FAILED になる"
                    + "（理由は Run 詳細に表示される）。")
    public FinalizeResponse finalizeRun(@PathVariable UUID runId) {
        // 確定（トランザクション）を先に終えてから判定する。判定に失敗しても確定は取り消さない
        ingestService.finalizeRun(runId);
        Run run = pipeline.evaluate(runId);
        return new FinalizeResponse(run.getId(), run.getStatus(), run.getVerdict(), run.getCompleteness(),
                run.getErrorCode(), ingestService.detailUrl(run.getId()));
    }

    private static ArtifactType parseType(String type) {
        try {
            return ArtifactType.fromWire(type);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.ARTIFACT_TYPE_UNKNOWN,
                    "未知の成果物種別です: %s（対応形式は docs/spec/02-metrics-spec.md 0.5 を参照）"
                            .formatted(type));
        }
    }

    private static String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "artifact" : name;
    }
}
