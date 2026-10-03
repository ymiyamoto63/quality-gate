package com.qualitygate.domain.metric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricCatalogTest {

    @Test
    void 指標をすべて持つ() {
        assertThat(MetricCatalog.all()).extracting(MetricDefinition::metricId)
                .containsExactly("M-01", "M-02", "M-03", "M-04",
                        "M-05", "M-06", "M-07", "M-08", "M-09", "M-10", "M-11", "M-12");
    }

    /**
     * 廃止された指標の判定結果を持つ過去の Run でもリリース判定が開けること。
     * 例外にすると、指標を一つ削っただけで過去の画面がすべて壊れる。
     */
    @Test
    void 未知の指標でも例外にしない() {
        MetricDefinition unknown = MetricCatalog.of("M-99");

        assertThat(unknown.metricId()).isEqualTo("M-99");
        assertThat(unknown.name()).isEqualTo("M-99");
    }

    @Test
    void 指標の並びは一覧と同じ順になる() {
        List<String> shuffled = new java.util.ArrayList<>(
                List.of("M-06", "M-01", "M-08", "M-05"));
        shuffled.sort(MetricCatalog::compareByCatalogOrder);

        assertThat(shuffled).containsExactly("M-01", "M-05", "M-06", "M-08");
    }

    @Test
    void 未知の指標は末尾に並ぶ() {
        List<String> ids = new java.util.ArrayList<>(List.of("M-99", "M-01"));
        ids.sort(MetricCatalog::compareByCatalogOrder);

        assertThat(ids).containsExactly("M-01", "M-99");
    }
}
