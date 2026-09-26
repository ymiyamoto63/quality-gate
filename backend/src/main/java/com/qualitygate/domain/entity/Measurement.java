package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.MeasurementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 指標ごとの実測値と判定。
 *
 * <p>{@code repositoryId} と {@code measuredAt} は {@link Run} からの意図的な複製である。
 * トレンド検索は期間・リポジトリ・指標で絞り込むため、毎回 {@code runs} と結合すると
 * 性能要件（p95 800ms）を満たしにくい。更新されない値の複製であり不整合の余地がない。
 */
@Entity
@Table(name = "measurements")
public class Measurement {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "metric_id", nullable = false, length = 8)
    private String metricId;

    /** シナリオ単位の判定（M-03 の性能シナリオなど）に使う。M-01 などでは null。 */
    @Column
    private String scenario;

    @Column(name = "component_name")
    private String componentName;

    /**
     * 計測条件（M-02 の実行範囲 changed / all など）。条件の違う値は比較できないため、
     * 前回比とトレンドの系列はこの値ごとに分ける。条件の区別が無い指標では null。
     */
    @Column
    private String variant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MeasurementStatus status;

    /** 未計測は null。0 は「計測して 0 だった」を意味し、意味が違う。 */
    @Column(precision = 12, scale = 4)
    private BigDecimal value;

    @Column
    private String unit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String threshold;

    @Column(name = "previous_value", precision = 12, scale = 4)
    private BigDecimal previousValue;

    /** 判定理由。画面にそのまま出す文であり、表現をサーバに集約する。 */
    @Column
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    protected Measurement() {
    }

    @SuppressWarnings("java:S107")
    public Measurement(UUID id, UUID runId, UUID repositoryId, String metricId,
                       String componentName, String variant, MeasurementStatus status,
                       BigDecimal value, String unit, String threshold,
                       BigDecimal previousValue, String reason, String detail,
                       Instant measuredAt) {
        this.id = id;
        this.runId = runId;
        this.repositoryId = repositoryId;
        this.metricId = metricId;
        this.componentName = componentName;
        this.variant = variant;
        this.status = status;
        this.value = value;
        this.unit = unit;
        this.threshold = threshold;
        this.previousValue = previousValue;
        this.reason = reason;
        this.detail = detail;
        this.measuredAt = measuredAt;
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

    /** コンポーネント名（backend / frontend）。全体値の指標では null。 */
    public String getComponentName() {
        return componentName;
    }

    public String getVariant() {
        return variant;
    }

    public String getScenario() {
        return scenario;
    }

    public MeasurementStatus getStatus() {
        return status;
    }

    public BigDecimal getValue() {
        return value;
    }

    public String getUnit() {
        return unit;
    }

    public String getThreshold() {
        return threshold;
    }

    public BigDecimal getPreviousValue() {
        return previousValue;
    }

    public String getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }
}
