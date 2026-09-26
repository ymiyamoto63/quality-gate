package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 1 つのコミットに対する 1 回の計測・判定。確定後は不変として扱う。
 *
 * <p>比較対象 Run（{@code baselineRunId}）と適用した設定版（{@code gateConfigId}）を
 * 保持することで、判定の再現性を担保する。
 */
@Entity
@Table(name = "runs")
public class Run {

    @Id
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Column(name = "base_commit_sha", length = 40)
    private String baseCommitSha;

    @Column(nullable = false)
    private String branch;

    @Column(name = "pull_request_number")
    private Integer pullRequestNumber;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "triggered_by", nullable = false)
    private String triggeredBy;

    @Column(name = "ci_run_url")
    private String ciRunUrl;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Column(name = "gate_config_id")
    private UUID gateConfigId;

    @Column(name = "baseline_run_id")
    private UUID baselineRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Enumerated(EnumType.STRING)
    @Column
    private Verdict verdict;

    @Enumerated(EnumType.STRING)
    @Column
    private Completeness completeness;

    /** ファイルの移動・リネームの対応表（JSON。新しいパス → 移動前のパス）。求めていなければ null。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "renamed_files", columnDefinition = "jsonb")
    private String renamedFiles;

    /** 計測したコミットを指すタグ（収集ランナーが計測時に求めて送る）。リリース判定でタグをコミットに解決するのに使う。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]", nullable = false)
    private List<String> tags = new ArrayList<>();

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    protected Run() {
    }

    public Run(UUID id, UUID repositoryId, String commitSha, String branch,
               String triggeredBy, Instant measuredAt, int attempt) {
        this.id = id;
        this.repositoryId = repositoryId;
        this.commitSha = commitSha;
        this.branch = branch;
        this.triggeredBy = triggeredBy;
        this.measuredAt = measuredAt;
        this.attempt = attempt;
        this.status = RunStatus.CREATED;
    }

    public void markUploading() {
        if (status == RunStatus.CREATED) {
            this.status = RunStatus.UPLOADING;
        }
    }

    public void finalizeIngest() {
        this.status = RunStatus.FINALIZED;
    }

    public void markProcessing() {
        this.status = RunStatus.PROCESSING;
    }

    /** 判定に使った設定版を記録する。判定の再現性の根拠になる。 */
    public void applyGateConfig(UUID gateConfigId) {
        this.gateConfigId = gateConfigId;
    }

    public UUID getGateConfigId() {
        return gateConfigId;
    }

    /**
     * 差分（新規 / 継続 / 解消）の算出に使った比較対象 Run を記録する。
     *
     * <p>「前回」が同一ブランチの直前の Run とは限らない（失敗した Run は
     * 比較対象にならない）ため、どの Run と比べた結果なのかを残さないと
     * 「新規 2 件」の根拠を後から辿れない。
     */
    public void applyBaseline(UUID baselineRunId) {
        this.baselineRunId = baselineRunId;
    }

    /** 比較対象 Run。初回 Run では null。 */
    public UUID getBaselineRunId() {
        return baselineRunId;
    }

    /** finalize されないまま滞留した Run を終端にする。ダッシュボードの最新から外す。 */
    public void markAbandoned() {
        this.status = RunStatus.ABANDONED;
    }

    /** 処理そのものが失敗した場合。判定結果 FAIL とは区別する。 */
    public void markFailed(String errorCode, String errorDetail) {
        this.status = RunStatus.FAILED;
        this.verdict = null;
        this.completeness = null;
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
        this.evaluatedAt = Instant.now();
    }

    public void markEvaluated(Verdict verdict, Completeness completeness, Instant at) {
        this.status = RunStatus.EVALUATED;
        this.verdict = verdict;
        this.completeness = completeness;
        this.evaluatedAt = at;
        // 処理失敗の後に再評価で判定できた場合、失敗の理由を残さない
        this.errorCode = null;
        this.errorDetail = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getBaseCommitSha() {
        return baseCommitSha;
    }

    public void setBaseCommitSha(String baseCommitSha) {
        this.baseCommitSha = baseCommitSha;
    }

    public String getRenamedFiles() {
        return renamedFiles;
    }

    public void setRenamedFiles(String renamedFiles) {
        this.renamedFiles = renamedFiles;
    }

    public List<String> getTags() {
        return List.copyOf(tags);
    }

    public void setTags(List<String> tags) {
        this.tags = new ArrayList<>(tags);
    }

    public String getBranch() {
        return branch;
    }

    public Integer getPullRequestNumber() {
        return pullRequestNumber;
    }

    public void setPullRequestNumber(Integer pullRequestNumber) {
        this.pullRequestNumber = pullRequestNumber;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getCiRunUrl() {
        return ciRunUrl;
    }

    public void setCiRunUrl(String ciRunUrl) {
        this.ciRunUrl = ciRunUrl;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public Completeness getCompleteness() {
        return completeness;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
