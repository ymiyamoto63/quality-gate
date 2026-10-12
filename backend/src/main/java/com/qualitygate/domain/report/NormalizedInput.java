package com.qualitygate.domain.report;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 判定エンジンへの入力。取り込んだ成果物をすべて正規化した結果。
 *
 * @param measurements   指標の素の値
 * @param findings       違反
 * @param metricsWithData 成果物が正常に読めた指標 ID
 * @param parseErrors    指標 ID → 読み取り失敗の理由
 * @param excludedFiles  指標 ID → {@code QG_EXCLUSIONS} に一致して読み捨てたファイルのパス（成果物をまたいで重複なし）。
 *                       違反だけを運ぶ成果物（SARIF）の分。測定値を返す成果物の分は測定値の内訳にある
 *                       （{@link NormalizedReport#excludedFiles()}）
 */
public record NormalizedInput(
        List<RawMeasurement> measurements,
        List<IdentifiedFinding> findings,
        Set<String> metricsWithData,
        Map<String, String> parseErrors,
        Map<String, Set<String>> excludedFiles) {

    public NormalizedInput {
        excludedFiles = excludedFiles == null ? Map.of() : excludedFiles.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    /** 計測除外で読み捨てたファイルが無い入力。 */
    public NormalizedInput(List<RawMeasurement> measurements, List<IdentifiedFinding> findings,
                           Set<String> metricsWithData, Map<String, String> parseErrors) {
        this(measurements, findings, metricsWithData, parseErrors, Map.of());
    }

    /** {@code QG_EXCLUSIONS} で読み捨てたファイルの数（違反だけを運ぶ成果物の分）。 */
    public int excludedFileCount(String metricId) {
        return excludedFiles.getOrDefault(metricId, Set.of()).size();
    }

    public List<IdentifiedFinding> findingsOf(String metricId) {
        return findings.stream().filter(f -> f.metricId().equals(metricId)).toList();
    }

    public List<RawMeasurement> measurementsOf(String metricId) {
        return measurements.stream().filter(m -> m.metricId().equals(metricId)).toList();
    }
}
