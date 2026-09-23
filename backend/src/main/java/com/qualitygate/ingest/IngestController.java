package com.qualitygate.ingest;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.ingest.dto.CreateRunRequest;
import com.qualitygate.ingest.dto.CreateRunResponse;
import com.qualitygate.ingest.dto.FinalizeResponse;
import com.qualitygate.ingest.dto.RunStatusResponse;
import com.qualitygate.ingest.dto.UploadArtifactResponse;
import com.qualitygate.ingest.security.IngestAuthentication;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * CI からの取り込み API。認証は Ingest Token（書き込み専用）。
 *
 * <p>{@code finalize} は判定の完了を待たずに応答する。判定には数十秒かかりうるため、
 * 同期にすると CI の待ち時間が延びる。
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Ingest", description = "CI からの計測結果の取り込み")
public class IngestController {

    private final IngestService ingestService;
    private final IngestMetrics metrics;

    public IngestController(IngestService ingestService, IngestMetrics metrics) {
        this.ingestService = ingestService;
        this.metrics = metrics;
    }

    @PostMapping
    @Operation(summary = "Run を作成する",
            description = "計測開始時に呼ぶ。同一コミットへの再送信は attempt を増やした新しい Run になる。")
    public ResponseEntity<CreateRunResponse> createRun(
            IngestAuthentication auth, @Valid @RequestBody CreateRunRequest request) {
        Run run;
        try {
            run = ingestService.createRun(auth.repositoryId(), request);
        } catch (ApiException e) {
            metrics.run("rejected");
            throw e;
        }
        metrics.run("created");
        CreateRunResponse body = new CreateRunResponse(
                run.getId(), run.getAttempt(), run.getStatus(), ingestService.detailUrl(run.getId()));
        return ResponseEntity.created(URI.create("/api/v1/runs/" + run.getId())).body(body);
    }

    @PostMapping(path = "/{runId}/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "成果物をアップロードする",
            description = "この時点ではパースしない。受領・検証・保存のみを行い、パースは判定ジョブで実施する。")
    public ResponseEntity<UploadArtifactResponse> uploadArtifact(
            IngestAuthentication auth,
            @PathVariable UUID runId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam(value = "component", required = false) String component,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "metadata", required = false) String metadata) {

        ArtifactType artifactType;
        try {
            artifactType = parseType(type);
        } catch (ApiException e) {
            metrics.artifact("rejected", "unknown");
            throw e;
        }
        try (InputStream content = file.getInputStream()) {
            ArtifactRecord record = ingestService.storeArtifact(auth.repositoryId(), runId,
                    artifactType, originalName(file), component, scope, metadata,
                    content, file.getSize());
            metrics.artifact("accepted", artifactType.wire());
            return ResponseEntity.accepted().body(new UploadArtifactResponse(
                    record.getId(), record.getSizeBytes(), record.getSha256()));
        } catch (ApiException e) {
            metrics.artifact("rejected", artifactType.wire());
            throw e;
        } catch (IOException e) {
            metrics.artifact("rejected", artifactType.wire());
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "アップロードされたファイルを読み取れませんでした: " + e.getMessage());
        }
    }

    @PostMapping("/{runId}/finalize")
    @Operation(summary = "取り込み完了を宣言する",
            description = "判定は非同期で開始される。CI は判定の完了を待たずに次へ進んでよい。")
    public ResponseEntity<FinalizeResponse> finalizeRun(IngestAuthentication auth,
                                                        @PathVariable UUID runId) {
        Run run = ingestService.finalizeRun(auth.repositoryId(), runId);
        metrics.run("finalized");
        return ResponseEntity.accepted().body(new FinalizeResponse(
                run.getId(), run.getStatus(), ingestService.detailUrl(run.getId())));
    }

    @GetMapping("/{runId}/status")
    @Operation(summary = "処理状態と判定結果を取得する（CI のポーリング用）")
    public RunStatusResponse status(IngestAuthentication auth, @PathVariable UUID runId) {
        Run run = ingestService.loadOwnedRun(auth.repositoryId(), runId);
        return new RunStatusResponse(run.getId(), run.getStatus(), run.getVerdict(),
                run.getCompleteness(), run.getErrorCode(), ingestService.detailUrl(run.getId()));
    }

    private static ArtifactType parseType(String type) {
        try {
            return ArtifactType.fromWire(type);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.ARTIFACT_TYPE_UNKNOWN,
                    "未知の成果物種別です: %s（対応形式は docs/initial/02-metrics-spec.md 0.5 を参照）"
                            .formatted(type));
        }
    }

    private static String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "artifact" : name;
    }
}
