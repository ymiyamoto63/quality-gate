package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Measurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MeasurementRepository extends JpaRepository<Measurement, UUID> {

    List<Measurement> findByRunId(UUID runId);

    List<Measurement> findByRunIdIn(java.util.Collection<UUID> runIds);

    void deleteByRunId(UUID runId);

    /**
     * トレンド用の時系列。
     *
     * <p>判定済み（{@code EVALUATED}）の Run だけを対象にする。処理に失敗した Run を
     * 含めると、品質の変化ではなく計測基盤の不調がグラフに現れる。
     *
     * <p>{@code measurements} は {@code repositoryId} と {@code measuredAt} を
     * {@code runs} から複製して持つが、ブランチとランナー種別は持たないため結合する。
     *
     * <p>対象外（{@code NOT_APPLICABLE}）は除く。測りようのないものは欠測ですらなく、
     * 系列を作ると値の無い線が 1 本増えるだけになる。
     */
    @Query("""
            select new com.qualitygate.domain.repo.TrendRow(
                m.runId, m.measuredAt, m.componentName, m.variant, r.runnerType, m.status,
                m.value, m.unit, m.threshold, r.commitSha)
            from Measurement m, Run r
            where r.id = m.runId
              and m.repositoryId = :repositoryId
              and m.metricId = :metricId
              and r.branch = :branch
              and r.status = com.qualitygate.domain.model.RunStatus.EVALUATED
              and m.status <> com.qualitygate.domain.model.MeasurementStatus.NOT_APPLICABLE
              and m.measuredAt >= :from
              and m.measuredAt < :to
            order by m.measuredAt asc, m.componentName asc
            """)
    List<TrendRow> findTrend(@Param("repositoryId") UUID repositoryId,
                             @Param("metricId") String metricId,
                             @Param("branch") String branch,
                             @Param("from") Instant from,
                             @Param("to") Instant to);
}
