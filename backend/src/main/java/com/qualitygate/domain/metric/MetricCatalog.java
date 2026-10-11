package com.qualitygate.domain.metric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指標の一覧と、指標 ID から名称を引くための唯一の定義（並びは docs/metrics.md 1 章の表と同じ）。
 *
 * <p>判定する指標の一覧（{@code GateThresholds.ALL_METRICS}）、各評価器・アダプタの指標 ID、
 * 指標の説明（{@link MetricGuide}）とリリース判定の表示がこれを使う。
 * 2 箇所に持つと、指標を足したときに片方だけ直して食い違う。
 */
public final class MetricCatalog {

    public static final String M_BRANCH_COVERAGE = "M-01";
    public static final String M_MUTATION = "M-02";
    public static final String M_PERFORMANCE_P95 = "M-03";
    public static final String M_ERROR_RATE = "M-04";
    public static final String M_VULNERABILITIES = "M-05";
    public static final String M_COMPLEXITY = "M-06";
    public static final String M_BREAKING_CHANGES = "M-07";
    public static final String M_ACCESSIBILITY = "M-08";
    public static final String M_TEST_SUCCESS = "M-09";
    public static final String M_SKIPPED_TESTS = "M-10";
    public static final String M_SECRETS = "M-11";
    public static final String M_LICENSES = "M-12";

    private static final List<MetricDefinition> ALL = List.of(
            new MetricDefinition(M_BRANCH_COVERAGE, "ブランチカバレッジ"),
            new MetricDefinition(M_MUTATION, "ミューテーションスコア"),
            new MetricDefinition(M_PERFORMANCE_P95, "応答時間 p95"),
            new MetricDefinition(M_ERROR_RATE, "エラー率"),
            new MetricDefinition(M_VULNERABILITIES, "重大・高 脆弱性件数"),
            new MetricDefinition(M_COMPLEXITY, "循環的複雑度が上限を超える関数の数"),
            new MetricDefinition(M_BREAKING_CHANGES, "破壊的変更件数"),
            new MetricDefinition(M_ACCESSIBILITY, "アクセシビリティ違反"),
            new MetricDefinition(M_TEST_SUCCESS, "テスト成功率"),
            new MetricDefinition(M_SKIPPED_TESTS, "スキップされたテスト数"),
            new MetricDefinition(M_SECRETS, "シークレット検出件数"),
            new MetricDefinition(M_LICENSES, "ライセンス違反件数"));

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
