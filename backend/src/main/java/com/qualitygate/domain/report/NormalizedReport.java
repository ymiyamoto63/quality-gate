package com.qualitygate.domain.report;

import com.qualitygate.domain.model.ArtifactType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * アダプタの出力。ツール中立な表現であり、ツール固有の概念を上位層に漏らさない。
 *
 * @param suppliedMetrics この成果物が実際に計測した指標 ID。null なら成果物の型が供給しうる指標すべて
 *        （{@link ArtifactType#metricIds()}）。同じ型でも計測した範囲が成果物ごとに違う場合に絞る
 *        （Trivy の SARIF は、走査した対象（脆弱性・シークレット・ライセンス）によって指標が変わる）。
 *        計測していない指標を「0 件」として合格にしないため
 */
public record NormalizedReport(
        ArtifactType type,
        List<RawMeasurement> measurements,
        List<RawFinding> findings,
        Map<String, String> metadata,
        Set<String> suppliedMetrics) {

    public static NormalizedReport of(ArtifactType type, List<RawMeasurement> measurements,
                                      List<RawFinding> findings) {
        return new NormalizedReport(type, measurements, findings, Map.of(), null);
    }

    /** 実際に計測した指標を明示する。 */
    public NormalizedReport withSuppliedMetrics(Set<String> metricIds) {
        return new NormalizedReport(type, measurements, findings, metadata, Set.copyOf(metricIds));
    }

    /** この成果物が値を与える指標 ID。 */
    public List<String> metricIdsWithData() {
        return suppliedMetrics == null ? type.metricIds() : List.copyOf(suppliedMetrics);
    }
}
