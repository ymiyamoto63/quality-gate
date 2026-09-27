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

    /** 同じ Run に同じ種別・同じファイル名で送られた成果物（再送は置き換える）。 */
    java.util.Optional<ArtifactRecord> findByRunIdAndTypeAndFilename(UUID runId,
                                                                  com.qualitygate.domain.model.ArtifactType type,
                                                                  String filename);
}
