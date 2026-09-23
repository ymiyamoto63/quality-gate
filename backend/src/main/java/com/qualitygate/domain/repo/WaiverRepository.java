package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Waiver;
import com.qualitygate.domain.model.WaiverScope;
import com.qualitygate.domain.model.WaiverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WaiverRepository extends JpaRepository<Waiver, UUID> {

    /** 判定に効いている免除。状態が ACTIVE でも期限を過ぎたものは含めない。 */
    @Query("select w from Waiver w where w.repositoryId = :repositoryId "
            + "and w.status = com.qualitygate.domain.model.WaiverStatus.ACTIVE "
            + "and w.expiresAt > :at")
    List<Waiver> findEffective(@Param("repositoryId") UUID repositoryId, @Param("at") Instant at);

    List<Waiver> findByRepositoryIdAndScopeAndMetricIdAndFingerprintAndStatus(
            UUID repositoryId, WaiverScope scope, String metricId, String fingerprint,
            WaiverStatus status);

    List<Waiver> findByRepositoryIdAndScopeAndMetricIdAndStatus(
            UUID repositoryId, WaiverScope scope, String metricId, WaiverStatus status);

    List<Waiver> findByStatusOrderByExpiresAtAsc(WaiverStatus status);

    List<Waiver> findByRepositoryIdAndStatusOrderByExpiresAtAsc(UUID repositoryId,
                                                               WaiverStatus status);

    List<Waiver> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);

    List<Waiver> findAllByOrderByCreatedAtDesc();

    /** 期限を過ぎたのにまだ ACTIVE のもの（日次バッチで EXPIRED にする）。 */
    List<Waiver> findByStatusAndExpiresAtLessThanEqual(WaiverStatus status, Instant at);

    /** 期限が近づいている有効な免除（期限 7 日前の通知に使う）。 */
    @Query("select w from Waiver w where w.status = com.qualitygate.domain.model.WaiverStatus.ACTIVE "
            + "and w.expiresAt > :from and w.expiresAt <= :to order by w.expiresAt")
    List<Waiver> findExpiringBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(w) from Waiver w where w.repositoryId = :repositoryId "
            + "and w.status = com.qualitygate.domain.model.WaiverStatus.ACTIVE "
            + "and w.expiresAt > :at")
    long countEffective(@Param("repositoryId") UUID repositoryId, @Param("at") Instant at);
}
