package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Measurement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MeasurementRepository extends JpaRepository<Measurement, UUID> {

    List<Measurement> findByRunId(UUID runId);

    /** 指標の推移。横軸に並べる計測の値をまとめて読む。 */
    List<Measurement> findByRunIdIn(Collection<UUID> runIds);
}
