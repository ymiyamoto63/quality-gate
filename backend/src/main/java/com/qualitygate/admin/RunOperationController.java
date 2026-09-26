package com.qualitygate.admin;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.pipeline.RunEvaluationPipeline;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.platform.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Run の再評価（FR-05-5）。管理者のみ。 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Operations", description = "再評価（管理者のみ）")
public class RunOperationController {

    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final RunEvaluationPipeline pipeline;
    private final CurrentUser currentUser;
    private final AuditLogger auditLogger;
    private final TransactionTemplate transactions;

    public RunOperationController(RunRepository runs, ArtifactRecordRepository artifacts,
                                  RunEvaluationPipeline pipeline,
                                  CurrentUser currentUser, AuditLogger auditLogger,
                                  TransactionTemplate transactions) {
        this.runs = runs;
        this.artifacts = artifacts;
        this.pipeline = pipeline;
        this.currentUser = currentUser;
        this.auditLogger = auditLogger;
        this.transactions = transactions;
    }

    @PostMapping("/api/v1/runs/{runId}/reevaluate")
    @Operation(summary = "Run を再評価する",
            description = "保存済みの成果物と、その Run とともに送られた設定を読み直して、その場で判定し直す。"
                    + "成果物が保持期間を過ぎて削除されていれば 409 ARTIFACTS_DELETED。"
                    + "判定に失敗した場合も 200 で、status が FAILED になる。")
    public ReevaluateResponse reevaluate(@PathVariable UUID runId) {
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
        // 依頼したことを先に確定させる（判定に失敗しても、誰が依頼したかは残る）。
        // 判定はトランザクションの外で行う（パースの間 DB のトランザクションを保持しない）
        transactions.executeWithoutResult(status -> auditLogger.record(actor,
                AuditAction.RUN_REEVALUATION_REQUESTED, "RUN", runId, null,
                Map.of("previousStatus", run.getStatus().name())));
        Run evaluated = pipeline.evaluate(runId);
        return new ReevaluateResponse(runId, evaluated.getStatus(), evaluated.getVerdict(),
                evaluated.getErrorCode());
    }

    @Schema(description = "再評価の結果")
    public record ReevaluateResponse(
            @NotNull UUID runId,
            @NotNull @Schema(description = "EVALUATED（判定済み）か FAILED（処理失敗）") RunStatus status,
            @NotNull @Schema(nullable = true, description = "判定結果。処理失敗なら null") Verdict verdict,
            @NotNull @Schema(nullable = true, description = "処理失敗の理由のコード") String errorCode) {
    }
}
