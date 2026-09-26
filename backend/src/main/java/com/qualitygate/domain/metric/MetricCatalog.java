package com.qualitygate.domain.metric;

import com.qualitygate.domain.model.MutationScope;

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
 * （docs/initial/07-api-design.md 4.2）。平坦な配列を返して画面側で分類すると、
 * 分類規則がサーバとクライアントに二重化する。
 */
public final class MetricCatalog {

    private static final List<MetricDefinition> ALL = List.of(
            new MetricDefinition("M-01", "ブランチカバレッジ", MetricCategory.FUNCTIONAL, true, false),
            new MetricDefinition("M-02", "ミューテーションスコア", MetricCategory.FUNCTIONAL, true, false),
            // 性能はランナーの性能に左右されるため、計測環境ごとに別系列にする（FR-08-2）
            new MetricDefinition("M-03", "応答時間 p95", MetricCategory.PERFORMANCE, false, true),
            new MetricDefinition("M-04", "スループット", MetricCategory.PERFORMANCE, true, true),
            new MetricDefinition("M-05", "エラー率", MetricCategory.PERFORMANCE, false, true),
            new MetricDefinition("M-06", "重大・高 脆弱性件数", MetricCategory.SECURITY, false, false),
            new MetricDefinition("M-07", "循環的複雑度 15 超の新規関数数",
                    MetricCategory.STRUCTURE, false, false),
            new MetricDefinition("M-08", "API 契約テスト成功率", MetricCategory.CONTRACT, true, false),
            new MetricDefinition("M-09", "破壊的変更件数", MetricCategory.CONTRACT, false, false),
            new MetricDefinition("M-10", "アクセシビリティ違反", MetricCategory.USABILITY, false, false),
            // 要件定義の後に追加した指標。カテゴリは既存の表に合わせる
            new MetricDefinition("M-11", "テスト成功率", MetricCategory.FUNCTIONAL, true, false),
            new MetricDefinition("M-12", "スキップされたテスト数", MetricCategory.FUNCTIONAL, false, false),
            new MetricDefinition("M-13", "シークレット検出件数", MetricCategory.SECURITY, false, false),
            new MetricDefinition("M-14", "ライセンス違反件数", MetricCategory.SECURITY, false, false),
            // 参考値の指標。合格ラインを持たず、値とトレンドだけを残す
            new MetricDefinition("M-15", "コード重複率", MetricCategory.STRUCTURE, false, false, true),
            new MetricDefinition("M-16", "Lighthouse パフォーマンススコア", MetricCategory.PERFORMANCE,
                    true, false, true),
            new MetricDefinition("M-17", "バンドルサイズ（gzip）", MetricCategory.PERFORMANCE, false, false, true));

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
    /** 参考値の指標か（{@link MetricDefinition#referenceOnly()}）。 */
    public static boolean isReferenceOnly(String metricId) {
        return of(metricId).referenceOnly();
    }

    public static MetricDefinition of(String metricId) {
        return BY_ID.getOrDefault(metricId,
                new MetricDefinition(metricId, metricId, MetricCategory.FUNCTIONAL, true, false));
    }

    /**
     * 計測条件（{@code variant}）の表示名。条件の区別が無ければ null。
     *
     * <p>Run 詳細とトレンドの両方が使う。画面側で対応表を持つと、条件を足したときに
     * 片方だけ「changed」のような生の値を出してしまう。
     */
    public static String variantLabel(String metricId, String variant) {
        if (variant == null) {
            return null;
        }
        if ("M-02".equals(metricId)) {
            return MutationScope.find(variant).map(MutationScope::label).orElse(variant);
        }
        return variant;
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
