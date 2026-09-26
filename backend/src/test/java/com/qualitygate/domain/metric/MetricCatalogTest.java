package com.qualitygate.domain.metric;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricCatalogTest {

    @Test
    void 要件定義の指標と追加した指標をすべて持つ() {
        assertThat(MetricCatalog.all()).extracting(MetricDefinition::metricId)
                .containsExactly("M-01", "M-02", "M-03", "M-04", "M-05",
                        "M-06", "M-07", "M-08", "M-09", "M-10", "M-11", "M-12", "M-13");
    }

    @Test
    void 指標からカテゴリを引ける() {
        assertThat(MetricCatalog.of("M-01").category()).isEqualTo(MetricCategory.FUNCTIONAL);
        assertThat(MetricCatalog.of("M-04").category()).isEqualTo(MetricCategory.PERFORMANCE);
        assertThat(MetricCatalog.of("M-08").category()).isEqualTo(MetricCategory.CONTRACT);
    }

    /**
     * 廃止された指標の判定結果を持つ過去の Run でも詳細が開けること。
     * 例外にすると、指標を一つ削っただけで過去の画面がすべて壊れる。
     */
    @Test
    void 未知の指標でも例外にしない() {
        MetricDefinition unknown = MetricCatalog.of("M-99");

        assertThat(unknown.metricId()).isEqualTo("M-99");
        assertThat(unknown.name()).isEqualTo("M-99");
        assertThat(unknown.category()).isNotNull();
    }

    @Test
    void 指標の並びは要件定義の表と同じ順になる() {
        List<String> shuffled = new java.util.ArrayList<>(
                List.of("M-07", "M-01", "M-09", "M-06"));
        shuffled.sort(MetricCatalog::compareByCatalogOrder);

        assertThat(shuffled).containsExactly("M-01", "M-06", "M-07", "M-09");
    }

    @Test
    void 未知の指標は末尾に並ぶ() {
        List<String> ids = new java.util.ArrayList<>(List.of("M-99", "M-01"));
        ids.sort(MetricCatalog::compareByCatalogOrder);

        assertThat(ids).containsExactly("M-01", "M-99");
    }

    /** 差分の良し悪しの向き。増えれば良い指標と、減れば良い指標がある。 */
    @Test
    void 値の向きを指標ごとに持つ() {
        assertThat(MetricCatalog.of("M-01").higherIsBetter()).isTrue();
        assertThat(MetricCatalog.of("M-03").higherIsBetter()).isFalse();
        assertThat(MetricCatalog.of("M-06").higherIsBetter()).isFalse();
    }

    @Test
    void カテゴリの宣言順が表示順になる() {
        assertThat(List.of(MetricCategory.values()).stream()
                .sorted(Comparator.naturalOrder())
                .map(MetricCategory::displayName).toList())
                .containsExactly("機能テスト", "性能テスト", "セキュリティ",
                        "コード構造", "契約・互換性", "使いやすさ");
    }
}
