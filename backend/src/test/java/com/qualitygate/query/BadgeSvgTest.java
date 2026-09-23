package com.qualitygate.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BadgeSvgTest {

    @Test
    void 状態ごとの文言と色でSVGを組み立てる() {
        String svg = BadgeSvg.render(BadgeSvg.Status.FAILING);

        assertThat(svg).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"")
                .contains("aria-label=\"quality gate: failing\"")
                .contains("fill=\"#e05d44\"")
                .endsWith("</svg>");
    }

    @Test
    void 幅は文言が長いほど広い() {
        assertThat(BadgeSvg.width("passing with warnings")).isGreaterThan(BadgeSvg.width("passing"));
        String svg = BadgeSvg.render(BadgeSvg.Status.PASSING);
        int total = BadgeSvg.width("quality gate") + BadgeSvg.width("passing");
        assertThat(svg).contains("width=\"" + total + "\"");
    }

    @Test
    void 小数点はロケールに依らずピリオドで書く() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertThat(BadgeSvg.render(BadgeSvg.Status.UNKNOWN)).doesNotContainPattern("x=\"\\d+,\\d\"");
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
