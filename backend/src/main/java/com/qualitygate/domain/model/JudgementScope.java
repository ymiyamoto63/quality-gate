package com.qualitygate.domain.model;

import java.util.List;

/**
 * 判定したときに判定の範囲を狭めていた設定（DD-27 / DD-28）。Run に焼き付け、リリース判定の「判定の条件」に出す。
 *
 * <p>合格ラインと同じく判定時の値を残す。今の設定を読むと、後から設定を変えたときに過去の判定の範囲が変わって見える。
 *
 * @param disabledMetrics    判定から外した指標（{@code QG_DISABLED_METRICS}）
 * @param exclusions         計測除外の glob パターン（{@code QG_EXCLUSIONS}）
 * @param mutationComponents M-02 の対象コンポーネント（{@code QG_MUTATION_COMPONENTS}）。空なら限定しない
 * @param perfScenarios      M-03 で判定すべきシナリオ（{@code QG_PERF_SCENARIOS}）。空ならシナリオの有無を確かめない
 * @param accessibilityPages M-08 で検査されているべきページ（{@code QG_ACCESSIBILITY_PAGES}）。空なら限定しない
 */
public record JudgementScope(List<String> disabledMetrics, List<String> exclusions, List<String> mutationComponents,
                             List<String> perfScenarios, List<String> accessibilityPages) {

    public JudgementScope {
        disabledMetrics = List.copyOf(disabledMetrics);
        exclusions = List.copyOf(exclusions);
        mutationComponents = List.copyOf(mutationComponents);
        perfScenarios = List.copyOf(perfScenarios);
        accessibilityPages = List.copyOf(accessibilityPages);
    }
}
