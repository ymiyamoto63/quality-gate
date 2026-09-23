package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.IngestToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngestTokenRepository extends JpaRepository<IngestToken, UUID> {

    /** prefix で 1 件に絞ってから、秘密部分を定数時間比較する。 */
    Optional<IngestToken> findByTokenPrefix(String tokenPrefix);

    List<IngestToken> findByRepositoryIdAndRevokedAtIsNull(UUID repositoryId);

    List<IngestToken> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);
}
