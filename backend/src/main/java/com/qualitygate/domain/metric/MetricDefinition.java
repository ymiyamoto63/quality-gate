package com.qualitygate.domain.metric;

/**
 * 指標の定義（docs/metrics.md）。
 *
 * @param metricId     指標 ID（{@code M-01} など）
 * @param name         画面に出す名称
 * @param perComponent コンポーネントごとに判定するか（docs/metrics.md 2.2）。そうなら、Run の作成時に申告された
 *                     コンポーネントのうち結果の無いものを ERROR にする（fail-closed）
 */
public record MetricDefinition(
        String metricId,
        String name,
        boolean perComponent) {
}
