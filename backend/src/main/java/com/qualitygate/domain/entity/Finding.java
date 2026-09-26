package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * 判定の根拠となる個別の違反。
 *
 * <p>解消された違反（{@link FindingState#RESOLVED}）も保存する。データ量は増えるが、
 * Run 詳細の表示が 1 クエリで済み、かつ Run が不変のスナップショットになる。
 * 比較対象 Run が保持期間を過ぎて削除されても表示が壊れない。
 */
@Entity
@Table(name = "findings")
public class Finding {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "metric_id", nullable = false, length = 8)
    private String metricId;

    /** Run をまたいで同一と見なすキー。行番号を含めない（行ずれで誤判定しないため）。 */
    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FindingState state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(nullable = false)
    private String title;

    @Column(name = "file_path")
    private String filePath;

    @Column
    private Integer line;

    @Column(name = "component_name")
    private String componentName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    protected Finding() {
    }

    @SuppressWarnings("java:S107")
    public Finding(UUID id, UUID runId, String metricId, String fingerprint, FindingState state,
                   Severity severity, String ruleId, String title, String filePath,
                   Integer line, String componentName, String detail) {
        this.id = id;
        this.runId = runId;
        this.metricId = metricId;
        this.fingerprint = fingerprint;
        this.state = state;
        this.severity = severity;
        this.ruleId = ruleId;
        this.title = title;
        this.filePath = filePath;
        this.line = line;
        this.componentName = componentName;
        this.detail = detail;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getMetricId() {
        return metricId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public FindingState getState() {
        return state;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getTitle() {
        return title;
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getLine() {
        return line;
    }

    public String getComponentName() {
        return componentName;
    }

    public String getDetail() {
        return detail;
    }
}
