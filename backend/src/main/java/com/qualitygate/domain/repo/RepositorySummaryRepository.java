package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.RepositorySummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RepositorySummaryRepository extends JpaRepository<RepositorySummary, UUID> {
}
