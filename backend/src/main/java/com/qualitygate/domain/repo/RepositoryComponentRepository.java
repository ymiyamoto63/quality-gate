package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.RepositoryComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryComponentRepository extends JpaRepository<RepositoryComponent, UUID> {

    List<RepositoryComponent> findByRepositoryIdOrderByDisplayOrderAscNameAsc(UUID repositoryId);

    Optional<RepositoryComponent> findByRepositoryIdAndName(UUID repositoryId, String name);

    long countByRepositoryId(UUID repositoryId);
}
