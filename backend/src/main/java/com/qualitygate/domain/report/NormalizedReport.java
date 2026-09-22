package com.qualitygate.domain.report;

import com.qualitygate.domain.model.ArtifactType;

import java.util.List;
import java.util.Map;

/** アダプタの出力。ツール中立な表現であり、ツール固有の概念を上位層に漏らさない。 */
public record NormalizedReport(
        ArtifactType type,
        List<RawMeasurement> measurements,
        List<RawFinding> findings,
        Map<String, String> metadata) {

    public static NormalizedReport of(ArtifactType type, List<RawMeasurement> measurements,
                                      List<RawFinding> findings) {
        return new NormalizedReport(type, measurements, findings, Map.of());
    }
}
