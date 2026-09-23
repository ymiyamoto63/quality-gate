package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Job;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /**
     * 実行待ちジョブを取得する。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} を使うのは、単一プロセス構成では不要でも、
     * 将来プロセスを増やしたときにここが壊れるためである。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select j from Job j where j.status = 'PENDING' and j.runAfter <= :now order by j.runAfter")
    List<Job> lockNextPending(@Param("now") Instant now, Pageable pageable);

    long countByStatus(com.qualitygate.domain.model.JobStatus status);

    /**
     * 同じ鍵の実行待ち・実行中のジョブが無ければ登録する。あれば何もしない（0 を返す）。
     *
     * <p>一意制約違反を例外で受けると、呼び出し元の業務トランザクションごと
     * ロールバック専用になる。免除の登録と同時に再評価を積むような場面で、
     * 「再評価が既に積まれていた」ことが登録の失敗になってはならない。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO jobs (id, type, dedup_key, payload, status, attempts, max_attempts,
                              run_after, created_at, updated_at)
            VALUES (:id, :type, :dedupKey, cast(:payload as jsonb), 'PENDING', 0, 5,
                    :runAfter, now(), now())
            ON CONFLICT (type, dedup_key)
                WHERE dedup_key IS NOT NULL AND status IN ('PENDING','RUNNING')
            DO NOTHING
            """)
    int insertIfAbsent(@Param("id") UUID id, @Param("type") String type,
                       @Param("dedupKey") String dedupKey, @Param("payload") String payload,
                       @Param("runAfter") Instant runAfter);

    java.util.Optional<Job> findFirstByTypeAndDedupKeyAndStatusIn(
            com.qualitygate.domain.model.JobType type, String dedupKey,
            java.util.Collection<com.qualitygate.domain.model.JobStatus> statuses);

    /** 保持期間を過ぎた成功済みジョブ。少量ずつ消す。 */
    @org.springframework.data.jpa.repository.Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM jobs WHERE id IN (
                SELECT id FROM jobs WHERE status = 'SUCCEEDED' AND updated_at < :before
                LIMIT :limit)
            """)
    int deleteSucceededBefore(@Param("before") Instant before, @Param("limit") int limit);

    @Query("select j from Job j where j.status = 'DEAD' order by j.updatedAt desc")
    List<Job> findDead(Pageable pageable);

    /** RUNNING のまま滞留したジョブ。プロセスの異常終了で取り残されたもの。 */
    @Query("select j from Job j where j.status = 'RUNNING' and j.lockedAt < :before")
    List<Job> findStale(@Param("before") Instant before);
}
