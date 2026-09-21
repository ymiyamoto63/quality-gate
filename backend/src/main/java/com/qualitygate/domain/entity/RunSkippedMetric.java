package com.qualitygate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * CI からのスキップ申告。
 *
 * <p>{@code accepted=false} は「申告はあったが設定で許容されていない」状態であり、
 * 当該指標は SKIP ではなく ERROR になる。申告の事実自体は記録に残し、
 * なぜ ERROR になったのかを Run 詳細で説明できるようにする。
 */
@Entity
@Table(name = "run_skipped_metrics")
public class RunSkippedMetric {

    @EmbeddedId
    private Key key;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private boolean accepted;

    protected RunSkippedMetric() {
    }

    public RunSkippedMetric(UUID runId, String metricId, String reason, boolean accepted) {
        this.key = new Key(runId, metricId);
        this.reason = reason;
        this.accepted = accepted;
    }

    public UUID getRunId() {
        return key.runId;
    }

    public String getMetricId() {
        return key.metricId;
    }

    public String getReason() {
        return reason;
    }

    public boolean isAccepted() {
        return accepted;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "run_id", nullable = false)
        private UUID runId;

        @Column(name = "metric_id", nullable = false, length = 8)
        private String metricId;

        protected Key() {
        }

        Key(UUID runId, String metricId) {
            this.runId = runId;
            this.metricId = metricId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(runId, other.runId)
                    && Objects.equals(metricId, other.metricId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(runId, metricId);
        }
    }
}
