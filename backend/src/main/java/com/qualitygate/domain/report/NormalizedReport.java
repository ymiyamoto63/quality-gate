package com.qualitygate.domain.report;

import com.qualitygate.domain.model.ArtifactType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * アダプタの出力。ツール中立な表現であり、ツール固有の概念を上位層に漏らさない。
 *
 * @param suppliedMetrics この成果物が実際に計測した指標 ID。null なら成果物の型が供給しうる指標すべて
 *        （{@link ArtifactType#metricIds()}）。同じ型でも計測した範囲が成果物ごとに違う場合に絞る
 *        （Trivy の SARIF は、走査した対象（脆弱性・シークレット・ライセンス）によって指標が変わる）。
 *        計測していない指標を「0 件」として合格にしないため
 * @param excludedFiles 指標 ID → {@code QG_EXCLUSIONS} に一致して読み捨てたファイルのパス（重複なし）。
 *        違反だけを運ぶ成果物（SARIF）は測定値の内訳（{@code detail}）を持てないため、ここで評価器に渡す。
 *        除外は結果を良く見せうるため、件数を画面に出す（docs/metrics.md 2.3）。測定値を返す成果物は
 *        従来どおり測定値の内訳に {@code excludedFiles} を載せ、ここは空のまま
 */
public record NormalizedReport(
        ArtifactType type,
        List<RawMeasurement> measurements,
        List<RawFinding> findings,
        Map<String, String> metadata,
        Set<String> suppliedMetrics,
        Map<String, Set<String>> excludedFiles) {

    public NormalizedReport {
        excludedFiles = excludedFiles == null ? Map.of() : copyOf(excludedFiles);
    }

    public static NormalizedReport of(ArtifactType type, List<RawMeasurement> measurements,
                                      List<RawFinding> findings) {
        return new NormalizedReport(type, measurements, findings, Map.of(), null, Map.of());
    }

    /** 実際に計測した指標を明示する。 */
    public NormalizedReport withSuppliedMetrics(Set<String> metricIds) {
        return new NormalizedReport(type, measurements, findings, metadata, Set.copyOf(metricIds), excludedFiles);
    }

    /** 計測除外で読み捨てたファイルを添える（指標 ID → パス）。 */
    public NormalizedReport withExcludedFiles(Map<String, Set<String>> paths) {
        return new NormalizedReport(type, measurements, findings, metadata, suppliedMetrics, paths);
    }

    private static Map<String, Set<String>> copyOf(Map<String, Set<String>> paths) {
        Map<String, Set<String>> copy = new TreeMap<>();
        paths.forEach((metricId, files) -> copy.put(metricId, Set.copyOf(new LinkedHashSet<>(files))));
        return Map.copyOf(copy);
    }

    /** この成果物が値を与える指標 ID。 */
    public List<String> metricIdsWithData() {
        return suppliedMetrics == null ? type.metricIds() : List.copyOf(suppliedMetrics);
    }
}
