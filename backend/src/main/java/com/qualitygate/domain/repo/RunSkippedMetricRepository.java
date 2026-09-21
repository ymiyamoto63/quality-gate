package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.RunSkippedMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunSkippedMetricRepository
        extends JpaRepository<RunSkippedMetric, RunSkippedMetric.Key> {

    List<RunSkippedMetric> findByKeyRunId(UUID runId);
}
