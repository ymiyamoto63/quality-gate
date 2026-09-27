package com.qualitygate.domain.report;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判定エンジンへの入力。取り込んだ成果物をすべて正規化した結果。
 *
 * @param measurements   指標の素の値
 * @param findings       違反
 * @param metricsWithData 成果物が正常に読めた指標 ID
 * @param parseErrors    指標 ID → 読み取り失敗の理由
 */
public record NormalizedInput(
        List<RawMeasurement> measurements,
        List<IdentifiedFinding> findings,
        Set<String> metricsWithData,
        Map<String, String> parseErrors) {

    public List<IdentifiedFinding> findingsOf(String metricId) {
        return findings.stream().filter(f -> f.metricId().equals(metricId)).toList();
    }

    public List<RawMeasurement> measurementsOf(String metricId) {
        return measurements.stream().filter(m -> m.metricId().equals(metricId)).toList();
    }
}
