package com.qualitygate.domain.metric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指標の一覧と、指標 ID から名称を引くための唯一の定義（並びは docs/metrics.md 1 章の表と同じ）。
 *
 * <p>判定する指標の一覧（{@code GateThresholds.ALL_METRICS}）とリリース判定の表示がこれを使う。
 * 2 箇所に持つと、指標を足したときに片方だけ直して食い違う。
 */
public final class MetricCatalog {

    private static final List<MetricDefinition> ALL = List.of(
            new MetricDefinition("M-01", "ブランチカバレッジ"),
            new MetricDefinition("M-02", "ミューテーションスコア"),
            new MetricDefinition("M-03", "応答時間 p95"),
            new MetricDefinition("M-04", "エラー率"),
            new MetricDefinition("M-05", "重大・高 脆弱性件数"),
            new MetricDefinition("M-06", "循環的複雑度 15 超の関数数"),
            new MetricDefinition("M-07", "破壊的変更件数"),
            new MetricDefinition("M-08", "アクセシビリティ違反"),
            new MetricDefinition("M-09", "テスト成功率"),
            new MetricDefinition("M-10", "スキップされたテスト数"),
            new MetricDefinition("M-11", "シークレット検出件数"),
            new MetricDefinition("M-12", "ライセンス違反件数"));

    private static final Map<String, MetricDefinition> BY_ID = index();

    private MetricCatalog() {
    }

    private static Map<String, MetricDefinition> index() {
        Map<String, MetricDefinition> byId = new LinkedHashMap<>();
        ALL.forEach(definition -> byId.put(definition.metricId(), definition));
        return Map.copyOf(byId);
    }

    public static List<MetricDefinition> all() {
        return ALL;
    }

    /**
     * 未知の指標 ID でも例外にしない。過去の Run が、その後に廃止された指標の
     * 判定結果を持っている場合に、リリース判定が開けなくなるのを避ける。
     */
    public static MetricDefinition of(String metricId) {
        return BY_ID.getOrDefault(metricId,
                new MetricDefinition(metricId, metricId));
    }

    /** 指標 ID をこの一覧の並び（M-01, M-02, …）で比較する。 */
    public static int compareByCatalogOrder(String left, String right) {
        return Integer.compare(indexOf(left), indexOf(right));
    }

    private static int indexOf(String metricId) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).metricId().equals(metricId)) {
                return i;
            }
        }
        return ALL.size();
    }
}
