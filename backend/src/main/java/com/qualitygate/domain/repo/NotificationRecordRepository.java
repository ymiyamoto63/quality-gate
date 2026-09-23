package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.NotificationRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, UUID> {

    Optional<NotificationRecord> findByRunIdAndEventAndChannelAndTarget(UUID runId, String event,
                                                                        String channel,
                                                                        String target);

    Optional<NotificationRecord> findByEventAndChannelAndTargetAndDedupKey(String event,
                                                                          String channel,
                                                                          String target,
                                                                          String dedupKey);

    List<NotificationRecord> findByRepositoryIdOrderBySentAtDesc(UUID repositoryId,
                                                                 Pageable pageable);
}
