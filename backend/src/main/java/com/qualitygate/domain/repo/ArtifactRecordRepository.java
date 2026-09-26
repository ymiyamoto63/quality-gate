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

    /** 保持期間を過ぎ、まだ実体を消していない成果物。 */
    @Query("select a from ArtifactRecord a where a.deletedAt is null and a.uploadedAt < :before "
            + "order by a.uploadedAt")
    List<ArtifactRecord> findFilesUploadedBefore(@Param("before") java.time.Instant before,
                                                 org.springframework.data.domain.Pageable pageable);

    boolean existsByStorageKey(String storageKey);

    /** 同じ Run に同じ種別・同じファイル名で送られた成果物（再送は置き換える）。 */
    java.util.Optional<ArtifactRecord> findByRunIdAndTypeAndFilename(UUID runId,
                                                                  com.qualitygate.domain.model.ArtifactType type,
                                                                  String filename);
}
