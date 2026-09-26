package com.qualitygate.admin;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.JobStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.job.JobEnqueuer;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.platform.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Run の再評価（FR-05-5）と、恒久的に失敗したジョブの確認・再実行。管理者のみ。 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Operations", description = "再評価とジョブの運用（管理者のみ）")
public class RunOperationController {

    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final JobRepository jobs;
    private final JobEnqueuer enqueuer;
    private final CurrentUser currentUser;
    private final AuditLogger auditLogger;

    public RunOperationController(RunRepository runs, ArtifactRecordRepository artifacts,
                                  JobRepository jobs, JobEnqueuer enqueuer,
                                  CurrentUser currentUser, AuditLogger auditLogger) {
        this.runs = runs;
        this.artifacts = artifacts;
        this.jobs = jobs;
        this.enqueuer = enqueuer;
        this.currentUser = currentUser;
        this.auditLogger = auditLogger;
    }

    @PostMapping("/api/v1/runs/{runId}/reevaluate")
    @Operation(summary = "Run を再評価する",
            description = "保存済みの成果物と、その Run とともに送られた設定を読み直して判定し直す。"
                    + "成果物が保持期間を過ぎて削除されていれば 409 ARTIFACTS_DELETED。")
    @Transactional
    public ResponseEntity<ReevaluateResponse> reevaluate(@PathVariable UUID runId) {
        var actor = currentUser.actor();
        Run run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Run", runId));
        if (run.getStatus() != RunStatus.EVALUATED && run.getStatus() != RunStatus.FAILED) {
            throw new ApiException(ErrorCode.RUN_NOT_EVALUABLE,
                    "取り込みが確定していない Run は再評価できません（status=%s）"
                            .formatted(run.getStatus()));
        }
        if (artifacts.findByRunId(runId).stream().anyMatch(a -> a.getDeletedAt() != null)) {
            throw new ApiException(ErrorCode.ARTIFACTS_DELETED,
                    "成果物が保持期間を過ぎて削除されているため、再評価できません");
        }
        UUID jobId = enqueuer.enqueueReevaluation(runId, "manual");
        auditLogger.record(actor, AuditAction.RUN_REEVALUATION_REQUESTED, "RUN", runId, null,
                Map.of("jobId", jobId.toString()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ReevaluateResponse(runId, run.getStatus(), jobId));
    }

    @GetMapping("/api/v1/jobs/dead")
    @Operation(summary = "恒久的に失敗したジョブを一覧する（新しい順に最大 100 件）")
    @Transactional(readOnly = true)
    public DeadJobList deadJobs() {
        return new DeadJobList(jobs.findDead(PageRequest.of(0, 100)).stream()
                .map(job -> new DeadJobItem(job.getId(), job.getType().name(), job.getDedupKey(),
                        job.getAttempts(), job.getLastError(), job.getCreatedAt(),
                        job.getUpdatedAt()))
                .toList());
    }

    @PostMapping("/api/v1/jobs/{jobId}/retry")
    @Operation(summary = "恒久的に失敗したジョブを再実行する")
    @Transactional
    public ResponseEntity<Void> retry(@PathVariable UUID jobId) {
        Job job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("ジョブ", jobId));
        if (job.getStatus() != JobStatus.DEAD) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "再実行できるのは恒久的に失敗したジョブだけです（status=%s）".formatted(job.getStatus()));
        }
        job.retry(Instant.now());
        return ResponseEntity.accepted().build();
    }

    @Schema(description = "再評価を受け付けた。判定は非同期で行う")
    public record ReevaluateResponse(
            @NotNull UUID runId,
            @NotNull @Schema(description = "受付時点の Run の状態") RunStatus status,
            @NotNull UUID jobId) {
    }

    public record DeadJobList(@NotNull List<DeadJobItem> items) {
    }

    public record DeadJobItem(
            @NotNull UUID jobId,
            @NotNull String type,
            @NotNull @Schema(nullable = true) String dedupKey,
            @NotNull int attempts,
            @NotNull @Schema(nullable = true) String lastError,
            @NotNull Instant createdAt,
            @NotNull Instant updatedAt) {
    }
}
