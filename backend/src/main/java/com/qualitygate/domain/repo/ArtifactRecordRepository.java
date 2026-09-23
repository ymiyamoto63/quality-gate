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

    boolean existsByRunIdAndTypeAndFilename(UUID runId,
                                            com.qualitygate.domain.model.ArtifactType type,
                                            String filename);

    /** 実体が残っている成果物の合計バイト数（ストレージ使用量のメトリクス）。 */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(a.sizeBytes), 0) from ArtifactRecord a where a.deletedAt is null")
    long sumStoredBytes();
}
