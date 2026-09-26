package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Finding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<Finding, UUID>, FindingSearch {

    List<Finding> findByRunId(UUID runId);

    void deleteByRunId(UUID runId);

    /** 状態ごとの件数（Run 詳細の「新規 2 ・ 継続 5 …」）。行を取らずに数えるだけ。 */
    @Query("select f.state, count(f) from Finding f where f.runId = :runId group by f.state")
    List<Object[]> countByState(@Param("runId") UUID runId);

    /** 指標ごとの違反件数。Run 詳細の各指標行に「違反 N 件を見る」を出すために使う。 */
    @Query("select f.metricId, count(f) from Finding f "
            + "where f.runId = :runId and f.state <> 'RESOLVED' group by f.metricId")
    List<Object[]> countByMetric(@Param("runId") UUID runId);

    /** 比較対象 Run の fingerprint 集合。差分（新規 / 継続 / 解消）の算出に使う。 */
    @Query("select f.fingerprint from Finding f where f.runId = :runId and f.state <> 'RESOLVED'")
    List<String> findActiveFingerprints(@Param("runId") UUID runId);
}
