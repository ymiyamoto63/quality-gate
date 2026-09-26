package com.qualitygate.domain.metric;

/**
 * 指標の定義（docs/initial/02-metrics-spec.md）。
 *
 * @param metricId 指標 ID（{@code M-01} など）
 * @param name     画面に出す名称
 * @param category 属するカテゴリ
 * @param higherIsBetter 値が大きいほど良いか。差分（前回比）の良し悪しの向きに使う
 * @param referenceOnly 合格ラインを持たず、値とトレンドだけを残す指標か（参考値の指標）。
 *        判定は常に REFERENCE で、Run の合否・部分計測・カテゴリの状態に影響しない。
 *        基準値が見えてから合格ラインを決めるための、蓄積の期間に使う
 */
public record MetricDefinition(
        String metricId,
        String name,
        MetricCategory category,
        boolean higherIsBetter,
        boolean referenceOnly) {

    public MetricDefinition(String metricId, String name, MetricCategory category,
                            boolean higherIsBetter) {
        this(metricId, name, category, higherIsBetter, false);
    }
}
