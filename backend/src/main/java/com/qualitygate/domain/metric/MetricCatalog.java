package com.qualitygate.domain.metric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指標 ID から名称・カテゴリを引くための唯一の定義。
 *
 * <p>判定側（カテゴリ別の集約）と参照側（Run 詳細の表）の両方がこれを使う。
 * 2 箇所に持つと、指標を足したときに片方だけ直して「その他」に落ちる。
 *
 * <p>カテゴリ分類をサーバに置くのは、画面がカテゴリ表として描かれるため
 * （docs/07-api-design.md 4.2）。平坦な配列を返して画面側で分類すると、
 * 分類規則がサーバとクライアントに二重化する。
 */
public final class MetricCatalog {

    private static final List<MetricDefinition> ALL = List.of(
            new MetricDefinition("M-01", "ブランチカバレッジ", MetricCategory.FUNCTIONAL, true),
            new MetricDefinition("M-02", "ミューテーションスコア", MetricCategory.FUNCTIONAL, true),
            new MetricDefinition("M-03", "応答時間 p95", MetricCategory.PERFORMANCE, false),
            new MetricDefinition("M-04", "スループット", MetricCategory.PERFORMANCE, true),
            new MetricDefinition("M-05", "エラー率", MetricCategory.PERFORMANCE, false),
            new MetricDefinition("M-06", "重大・高 脆弱性件数", MetricCategory.SECURITY, false),
            new MetricDefinition("M-07", "循環的複雑度 15 超の新規関数数", MetricCategory.STRUCTURE, false),
            new MetricDefinition("M-08", "API 契約テスト成功率", MetricCategory.CONTRACT, true),
            new MetricDefinition("M-09", "破壊的変更件数", MetricCategory.CONTRACT, false),
            new MetricDefinition("M-10", "アクセシビリティ違反", MetricCategory.USABILITY, false));

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
     * 判定結果を持っている場合に、Run 詳細が開けなくなるのを避ける。
     */
    public static MetricDefinition of(String metricId) {
        return BY_ID.getOrDefault(metricId,
                new MetricDefinition(metricId, metricId, MetricCategory.FUNCTIONAL, true));
    }

    /** 指標 ID を要件定義の並び（M-01, M-02, …）で比較する。 */
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
