package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Finding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

    List<Finding> findByRunId(UUID runId);

    void deleteByRunId(UUID runId);

    /** 比較対象 Run の fingerprint 集合。差分（新規 / 継続 / 解消）の算出に使う。 */
    @Query("select f.fingerprint from Finding f where f.runId = :runId and f.state <> 'RESOLVED'")
    List<String> findActiveFingerprints(@Param("runId") UUID runId);
}
