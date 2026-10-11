package com.qualitygate.domain.metric;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 保存する_JSON_の形は過去の行と同じ() {
        assertThat(json(Threshold.atLeast(new BigDecimal("75"))))
                .isEqualTo("{\"operator\":\">=\",\"value\":75}");
        assertThat(json(Threshold.atMost(0).with("maxComplexity", 15)))
                .isEqualTo("{\"operator\":\"<=\",\"value\":0,\"maxComplexity\":15}");
        assertThat(json(Threshold.increaseAtMost(0)))
                .isEqualTo("{\"operator\":\"<=\",\"value\":0,\"basis\":\"increase\"}");
        assertThat(json(Threshold.none())).isEqualTo("{}");
    }

    @Test
    void 保存済みの行を読み戻せる() {
        Threshold threshold = Threshold.parse(objectMapper,
                "{\"operator\":\"<=\",\"value\":0,\"maxCritical\":0,\"maxHigh\":0}");

        assertThat(threshold.operator()).isEqualTo(Threshold.AT_MOST);
        assertThat(threshold.value()).isEqualByComparingTo("0");
        assertThat(threshold.isIncrease()).isFalse();
        assertThat(threshold.extras()).containsOnlyKeys("maxCritical", "maxHigh");
        assertThat(Threshold.parse(objectMapper, "{\"operator\":\"<=\",\"value\":2,\"basis\":\"increase\"}")
                .isIncrease()).isTrue();
    }

    @Test
    void 空や型の合わない項目は無いものとして読む() {
        assertThat(Threshold.parse(objectMapper, null)).isEqualTo(Threshold.none());
        assertThat(Threshold.parse(objectMapper, " ")).isEqualTo(Threshold.none());

        Threshold broken = Threshold.parse(objectMapper, "{\"operator\":1,\"value\":\"abc\"}");
        assertThat(broken.operator()).isNull();
        assertThat(broken.value()).isNull();
    }

    @Test
    void 下限を下げた合格ラインは既定より緩い() {
        Threshold relaxed = Threshold.atLeast(new BigDecimal("70"))
                .relaxedFrom(Threshold.atLeast(new BigDecimal("75")));

        assertThat(relaxed.isRelaxed()).isTrue();
        assertThat(relaxed.defaults()).contains(Threshold.atLeast(new BigDecimal("75")));
        assertThat(json(relaxed)).isEqualTo(
                "{\"operator\":\">=\",\"value\":70,\"relaxed\":true,\"default\":{\"operator\":\">=\",\"value\":75}}");
    }

    @Test
    void 上限を上げた合格ラインは既定より緩い() {
        assertThat(Threshold.atMost(1).relaxedFrom(Threshold.atMost(0)).isRelaxed()).isTrue();
        // 前回からの増加で判定する合格ラインも、向きと値だけで比べる
        assertThat(Threshold.increaseAtMost(2).relaxedFrom(Threshold.increaseAtMost(0)).isRelaxed()).isTrue();
    }

    @Test
    void 既定と同じか厳しい合格ラインには印を付けない() {
        Threshold same = Threshold.atLeast(new BigDecimal("75.0"));
        assertThat(same.relaxedFrom(Threshold.atLeast(new BigDecimal("75")))).isSameAs(same);
        assertThat(Threshold.atLeast(new BigDecimal("80")).relaxedFrom(Threshold.atLeast(new BigDecimal("75")))
                .isRelaxed()).isFalse();
        assertThat(Threshold.atMost(0).relaxedFrom(Threshold.atMost(1)).isRelaxed()).isFalse();
        // 比べ方の違うもの、合格ラインを持たないものは比べない
        assertThat(Threshold.increaseAtMost(5).relaxedFrom(Threshold.atMost(0)).isRelaxed()).isFalse();
        assertThat(Threshold.none().relaxedFrom(Threshold.atMost(0)).isRelaxed()).isFalse();
    }

    @Test
    void 上限や下限の補足が緩ければ既定より緩い() {
        Threshold standard = Threshold.atMost(0).with("maxComplexity", 15);
        Threshold relaxed = Threshold.atMost(0).with("maxComplexity", 20).relaxedFrom(standard);

        assertThat(relaxed.isRelaxed()).isTrue();
        assertThat(relaxed.isValueLooserThan(standard)).isFalse();
        assertThat(relaxed.looserLimitsThan(standard)).containsExactly("maxComplexity");
        // 下限の補足（最小実行件数）は、下げると緩い
        assertThat(Threshold.atLeast(BigDecimal.TEN).with("minTestCount", 1)
                .relaxedFrom(Threshold.atLeast(BigDecimal.TEN).with("minTestCount", 5)).isRelaxed()).isTrue();
        // 上限・下限でない補足は比べない
        assertThat(Threshold.atMost(0).with("standard", "WCAG 2.1")
                .relaxedFrom(Threshold.atMost(0).with("standard", "WCAG 2.2")).isRelaxed()).isFalse();
    }

    @Test
    void 緩いと記録した合格ラインを読み戻せる() {
        Threshold relaxed = Threshold.atMost(0).with("maxComplexity", 20)
                .relaxedFrom(Threshold.atMost(0).with("maxComplexity", 15));

        Threshold read = Threshold.parse(objectMapper, json(relaxed));

        assertThat(read.isRelaxed()).isTrue();
        Threshold standard = read.defaults().orElseThrow();
        assertThat(standard.extraValue("maxComplexity")).isEqualByComparingTo("15");
        assertThat(read.looserLimitsThan(standard)).containsExactly("maxComplexity");
        // 印の無い過去の行は緩くないものとして読む
        assertThat(Threshold.parse(objectMapper, "{\"operator\":\">=\",\"value\":60}").isRelaxed()).isFalse();
    }

    private String json(Threshold threshold) {
        return objectMapper.writeValueAsString(threshold.toMap());
    }
}
