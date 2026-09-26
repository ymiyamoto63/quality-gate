package com.qualitygate.domain.metric;

/**
 * 指標の定義（docs/spec/02-metrics-spec.md）。
 *
 * @param metricId 指標 ID（{@code M-01} など）
 * @param name     画面に出す名称
 * @param category 属するカテゴリ
 * @param higherIsBetter 値が大きいほど良いか。差分（前回比）の良し悪しの向きに使う
 */
public record MetricDefinition(
        String metricId,
        String name,
        MetricCategory category,
        boolean higherIsBetter) {
}
