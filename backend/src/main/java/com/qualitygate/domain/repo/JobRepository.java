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
}
