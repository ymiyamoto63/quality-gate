package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.JudgementScope;
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
import java.util.Optional;
import java.util.UUID;

/**
 * 1 つのコミットに対する 1 回の計測・判定。確定後は不変として扱う。
 *
 * <p>比較対象 Run（{@code baselineRunId}）と、指標ごとの合格ライン（measurements.threshold）を残すことで、
 * 判定の根拠を後から辿れるようにする。
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

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "ci_run_url")
    private String ciRunUrl;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Column(name = "baseline_run_id")
    private UUID baselineRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Enumerated(EnumType.STRING)
    @Column
    private Verdict verdict;

    /** 計測したコミットを指すタグ（収集ランナーが計測時に求めて送る）。リリース判定でタグをコミットに解決するのに使う。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]", nullable = false)
    private List<String> tags = new ArrayList<>();

    /**
     * 成果物を送るはずのコンポーネント（収集ランナーが Run の作成時に申告する。DD-26）。
     * コンポーネントごとに判定する指標で、成果物の届かなかったコンポーネントを ERROR にするのに使う。
     * この列を足す前（V003）に作られた Run では null（分からない）。
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "components", columnDefinition = "text[]")
    private List<String> components;

    /**
     * 判定したときに {@code QG_DISABLED_METRICS} で判定から外していた指標（DD-27）。
     * 合格ラインと同じく判定時に焼き付け、後から設定を変えても「何を判定しなかったか」が変わらないようにする。
     * V003 より前に判定した Run では null（分からない）。
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "disabled_metrics", columnDefinition = "text[]")
    private List<String> disabledMetrics;

    /**
     * 判定したときの計測除外（{@code QG_EXCLUSIONS}）の glob パターン（DD-28）。除外は結果を良く見せうるため、
     * 判定の条件としてリリース判定に出す。V004 より前に判定した Run では null（分からない）。
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "exclusions", columnDefinition = "text[]")
    private List<String> exclusions;

    /** 判定したときの M-02 の対象コンポーネント（{@code QG_MUTATION_COMPONENTS}。DD-28）。V004 より前の Run では null。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "mutation_components", columnDefinition = "text[]")
    private List<String> mutationComponents;

    /** 判定したときの M-03 の判定すべきシナリオ（{@code QG_PERF_SCENARIOS}。DD-28）。V004 より前の Run では null。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "perf_scenarios", columnDefinition = "text[]")
    private List<String> perfScenarios;

    /** 判定したときの M-08 の検査すべきページ（{@code QG_ACCESSIBILITY_PAGES}。DD-28）。V004 より前の Run では null。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "accessibility_pages", columnDefinition = "text[]")
    private List<String> accessibilityPages;

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
               Instant measuredAt, int attempt) {
        this.id = id;
        this.repositoryId = repositoryId;
        this.commitSha = commitSha;
        this.branch = branch;
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

    /** 処理そのものが失敗した場合。判定結果 FAIL とは区別する。 */
    public void markFailed(String errorCode, String errorDetail) {
        this.status = RunStatus.FAILED;
        this.verdict = null;
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
        this.evaluatedAt = Instant.now();
    }

    /**
     * 判定の範囲を狭める設定を記録する（判定のたびに、そのときの設定で上書きする。DD-27 / DD-28）。
     */
    public void applyScope(JudgementScope scope) {
        this.disabledMetrics = new ArrayList<>(scope.disabledMetrics());
        this.exclusions = new ArrayList<>(scope.exclusions());
        this.mutationComponents = new ArrayList<>(scope.mutationComponents());
        this.perfScenarios = new ArrayList<>(scope.perfScenarios());
        this.accessibilityPages = new ArrayList<>(scope.accessibilityPages());
    }

    public void markEvaluated(Verdict verdict, Instant at) {
        this.status = RunStatus.EVALUATED;
        this.verdict = verdict;
        this.evaluatedAt = at;
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

    public List<String> getTags() {
        return List.copyOf(tags);
    }

    public void setTags(List<String> tags) {
        this.tags = new ArrayList<>(tags);
    }

    /** 申告されたコンポーネント。V003 より前に作られた Run では空（分からない）。 */
    public Optional<List<String>> getComponents() {
        return Optional.ofNullable(components).map(List::copyOf);
    }

    public void setComponents(List<String> components) {
        this.components = components == null ? null : new ArrayList<>(components);
    }

    /** 判定から外した指標。V003 より前に判定した Run では空（分からない）。 */
    public Optional<List<String>> getDisabledMetrics() {
        return Optional.ofNullable(disabledMetrics).map(List::copyOf);
    }

    /**
     * 判定したときの判定の範囲。範囲を狭める設定を焼き付ける前（V004 より前）に判定した Run では空（分からない）。
     * 判定から外した指標だけは V003 から記録しているため、{@link #getDisabledMetrics()} で別に読める。
     */
    public Optional<JudgementScope> getScope() {
        if (disabledMetrics == null || exclusions == null || mutationComponents == null || perfScenarios == null
                || accessibilityPages == null) {
            return Optional.empty();
        }
        return Optional.of(new JudgementScope(disabledMetrics, exclusions, mutationComponents, perfScenarios,
                accessibilityPages));
    }

    public String getBranch() {
        return branch;
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
