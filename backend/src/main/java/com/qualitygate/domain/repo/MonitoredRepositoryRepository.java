package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.MonitoredRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitoredRepositoryRepository extends JpaRepository<MonitoredRepository, UUID> {

    Optional<MonitoredRepository> findByOwnerAndName(String owner, String name);

    List<MonitoredRepository> findByEnabledTrueOrderByOwnerAscNameAsc();

    Optional<MonitoredRepository> findByOwnerIgnoreCaseAndNameIgnoreCase(String owner, String name);

    List<MonitoredRepository> findAllByOrderByOwnerAscNameAsc();
}
