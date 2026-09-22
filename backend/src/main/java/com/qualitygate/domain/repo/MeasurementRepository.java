package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Measurement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MeasurementRepository extends JpaRepository<Measurement, UUID> {

    List<Measurement> findByRunId(UUID runId);

    void deleteByRunId(UUID runId);
}
