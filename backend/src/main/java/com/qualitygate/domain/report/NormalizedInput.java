package com.qualitygate.domain.report;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判定エンジンへの入力。取り込んだ成果物をすべて正規化した結果。
 *
 * @param measurements   指標の素の値
 * @param headFindings   対象コミット側の違反
 * @param baseFindings   ベースコミット側の違反（scope=base で提出されたもの）
 * @param metricsWithData 成果物が正常に読めた指標 ID
 * @param parseErrors    指標 ID → 読み取り失敗の理由
 * @param previousFingerprints 対象コミット側の違反の fingerprint → ファイルの移動・リネーム前の fingerprint
 *                       （指標仕様書 0.4。移動していない違反は含めない）
 */
public record NormalizedInput(
        List<RawMeasurement> measurements,
        List<IdentifiedFinding> headFindings,
        List<IdentifiedFinding> baseFindings,
        Set<String> metricsWithData,
        Map<String, String> parseErrors,
        Map<String, String> previousFingerprints) {

    public NormalizedInput(List<RawMeasurement> measurements, List<IdentifiedFinding> headFindings,
                           List<IdentifiedFinding> baseFindings, Set<String> metricsWithData,
                           Map<String, String> parseErrors) {
        this(measurements, headFindings, baseFindings, metricsWithData, parseErrors, Map.of());
    }

    /** ファイルの移動・リネーム前の fingerprint。移動していなければ null。 */
    public String previousFingerprintOf(String fingerprint) {
        return previousFingerprints.get(fingerprint);
    }

    public List<IdentifiedFinding> headFindingsOf(String metricId) {
        return headFindings.stream().filter(f -> f.metricId().equals(metricId)).toList();
    }

    public List<IdentifiedFinding> baseFindingsOf(String metricId) {
        return baseFindings.stream().filter(f -> f.metricId().equals(metricId)).toList();
    }

    public List<RawMeasurement> measurementsOf(String metricId) {
        return measurements.stream().filter(m -> m.metricId().equals(metricId)).toList();
    }
}
