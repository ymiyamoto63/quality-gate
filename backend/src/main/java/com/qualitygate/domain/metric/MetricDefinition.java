package com.qualitygate.domain.metric;

/**
 * 指標の定義（docs/metrics.md）。
 *
 * @param metricId 指標 ID（{@code M-01} など）
 * @param name     画面に出す名称
 * @param category 属するカテゴリ
 */
public record MetricDefinition(
        String metricId,
        String name,
        MetricCategory category) {
}
