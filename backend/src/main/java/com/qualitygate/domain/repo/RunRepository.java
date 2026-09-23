package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.RunStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository extends JpaRepository<Run, UUID> {

    @Query("select coalesce(max(r.attempt), 0) from Run r "
            + "where r.repositoryId = :repositoryId and r.commitSha = :commitSha")
    int findMaxAttempt(@Param("repositoryId") UUID repositoryId, @Param("commitSha") String commitSha);

    Optional<Run> findFirstByRepositoryIdAndBranchAndStatusOrderByMeasuredAtDesc(
            UUID repositoryId, String branch, RunStatus status);

    Optional<Run> findFirstByRepositoryIdAndBranchAndStatusAndMeasuredAtLessThanOrderByMeasuredAtDesc(
            UUID repositoryId, String branch, RunStatus status, Instant measuredAt);

    List<Run> findByRepositoryIdOrderByMeasuredAtDesc(UUID repositoryId, Pageable pageable);

    /**
     * カーソル以降の 1 ページ。
     *
     * <p>{@code measuredAt} は同時刻が起こりうる（同じコミットの再計測など）ため、
     * id を第 2 の鍵にして境界をまたいだ重複・欠落を防ぐ。
     */
    @Query("select r from Run r where r.repositoryId = :repositoryId "
            + "and (r.measuredAt < :measuredAt "
            + "     or (r.measuredAt = :measuredAt and r.id < :id)) "
            + "order by r.measuredAt desc, r.id desc")
    List<Run> findPageAfter(@Param("repositoryId") UUID repositoryId,
                            @Param("measuredAt") Instant measuredAt,
                            @Param("id") UUID id,
                            Pageable pageable);

    /** finalize されないまま滞留した Run（ABANDONED の対象）。 */
    @Query("select r from Run r where r.status in ('CREATED','UPLOADING') and r.createdAt < :before")
    List<Run> findStaleRuns(@Param("before") Instant before);
}
