package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * 新しい順の 1 ページ。{@code occurredAt} と id のキーセットで送る。
     *
     * <p>{@code action} が null なら操作種別で絞り込まない。
     */
    @Query("""
            select a from AuditLog a
            where a.occurredAt >= :from and a.occurredAt < :to
              and (:action is null or a.action = :action)
              and (a.occurredAt < :cursorAt or (a.occurredAt = :cursorAt and a.id < :cursorId))
            order by a.occurredAt desc, a.id desc
            """)
    List<AuditLog> findPage(@Param("from") Instant from, @Param("to") Instant to,
                            @Param("action") String action,
                            @Param("cursorAt") Instant cursorAt, @Param("cursorId") UUID cursorId,
                            Pageable pageable);

    List<AuditLog> findByTargetTypeAndTargetIdOrderByOccurredAtDesc(String targetType,
                                                                    String targetId);
}
