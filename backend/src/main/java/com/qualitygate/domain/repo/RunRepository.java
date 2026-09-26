package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.RunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository extends JpaRepository<Run, UUID> {

    /** 判定の間、同じ Run の判定（再評価の二重押しなど）を待たせる。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Run r where r.id = :id")
    Optional<Run> findByIdForUpdate(@Param("id") UUID id);

    @Query("select coalesce(max(r.attempt), 0) from Run r "
            + "where r.repositoryId = :repositoryId and r.commitSha = :commitSha")
    int findMaxAttempt(@Param("repositoryId") UUID repositoryId, @Param("commitSha") String commitSha);

    Optional<Run> findFirstByRepositoryIdAndBranchAndStatusAndMeasuredAtLessThanOrderByMeasuredAtDesc(
            UUID repositoryId, String branch, RunStatus status, Instant measuredAt);

    Optional<Run> findFirstByRepositoryIdAndStatusOrderByMeasuredAtDesc(UUID repositoryId, RunStatus status);

    /** 同じコミットで判定済みの Run のうち、最後の試行。 */
    Optional<Run> findFirstByRepositoryIdAndCommitShaAndStatusOrderByAttemptDesc(UUID repositoryId, String commitSha,
                                                                              RunStatus status);

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

    /** リリース判定（UC-06）。短い SHA の前方一致で、このリポジトリで計測したコミットを探す。 */
    @Query("select distinct r.commitSha from Run r where r.repositoryId = :repositoryId "
            + "and r.commitSha like :prefix")
    List<String> findCommitShasLike(@Param("repositoryId") UUID repositoryId, @Param("prefix") String prefix);

    /**
     * リリース判定（UC-06）。タグを付けて計測したコミットを探す。タグが付け替えられていれば、最も新しい計測のコミット。
     */
    @Query(value = "select r.commit_sha from runs r where r.repository_id = :repositoryId "
            + "and r.tags @> array[cast(:tag as text)] order by r.measured_at desc, r.attempt desc limit 1",
            nativeQuery = true)
    Optional<String> findLatestCommitShaByTag(@Param("repositoryId") UUID repositoryId, @Param("tag") String tag);

    /** リリース判定（UC-06）。同じコミットの Run を新しい順に返す。 */
    List<Run> findByRepositoryIdAndCommitShaOrderByMeasuredAtDescAttemptDesc(UUID repositoryId, String commitSha);
}
