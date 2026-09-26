package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.GateConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GateConfigRepository extends JpaRepository<GateConfig, UUID> {

    /** 内容が同じ設定は版を増やさない。変更履歴がノイズで埋まるため。 */
    Optional<GateConfig> findByRepositoryIdAndContentHash(UUID repositoryId, String contentHash);

    Optional<GateConfig> findFirstByRepositoryIdOrderByVersionDesc(UUID repositoryId);

    java.util.List<GateConfig> findByRepositoryIdOrderByVersionDesc(UUID repositoryId);

    @Query("select coalesce(max(c.version), 0) from GateConfig c where c.repositoryId = :repositoryId")
    int findMaxVersion(@Param("repositoryId") UUID repositoryId);
}
