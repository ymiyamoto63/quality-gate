package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.ArtifactRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ArtifactRecordRepository extends JpaRepository<ArtifactRecord, UUID> {

    List<ArtifactRecord> findByRunId(UUID runId);

    @Query("select coalesce(sum(a.sizeBytes), 0) from ArtifactRecord a where a.runId = :runId")
    long sumSizeBytesByRunId(@Param("runId") UUID runId);

    boolean existsByRunIdAndTypeAndFilename(UUID runId,
                                            com.qualitygate.domain.model.ArtifactType type,
                                            String filename);
}
