package com.qualitygate.domain.metric;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThresholdTest {

    private static final String COMPLEXITY = "関数ごとの循環的複雑度の上限";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 保存する_JSON_の形は過去の行と同じ() {
        assertThat(json(Threshold.atLeast(new BigDecimal("75"))))
                .isEqualTo("{\"operator\":\">=\",\"value\":75}");
        assertThat(json(Threshold.atMost(0).withLimit("maxComplexity", Threshold.AT_MOST, COMPLEXITY, 15)))
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
        Threshold standard = Threshold.atMost(0).withLimit("maxComplexity", Threshold.AT_MOST, COMPLEXITY, 15);
        Threshold relaxed = Threshold.atMost(0).withLimit("maxComplexity", Threshold.AT_MOST, COMPLEXITY, 20)
                .relaxedFrom(standard);

        assertThat(relaxed.isRelaxed()).isTrue();
        assertThat(relaxed.isValueLooserThan(standard)).isFalse();
        assertThat(relaxed.looserLimitsThan(standard)).containsExactly("maxComplexity");
        // 下限の補足（最小実行件数）は、下げると緩い
        assertThat(Threshold.atLeast(BigDecimal.TEN).withLimit("minTestCount", Threshold.AT_LEAST, "最小実行件数", 1)
                .relaxedFrom(Threshold.atLeast(BigDecimal.TEN)
                        .withLimit("minTestCount", Threshold.AT_LEAST, "最小実行件数", 5)).isRelaxed()).isTrue();
        // 上限・下限でない補足は比べない
        assertThat(Threshold.atMost(0).with("standard", "WCAG 2.1")
                .relaxedFrom(Threshold.atMost(0).with("standard", "WCAG 2.2")).isRelaxed()).isFalse();
    }

    /** 判定器が宣言した上限・下限は、ほかのどこにも登録しなくても緩さの比較と表示の名称に効く。 */
    @Test
    void 判定器が宣言しただけの上限も緩さを比べる() {
        Threshold standard = Threshold.atMost(0).withLimit("maxWidgets", Threshold.AT_MOST, "ウィジェットの上限", 3);
        Threshold relaxed = Threshold.atMost(0).withLimit("maxWidgets", Threshold.AT_MOST, "ウィジェットの上限", 9)
                .relaxedFrom(standard);

        assertThat(relaxed.isRelaxed()).isTrue();
        assertThat(relaxed.looserLimitsThan(standard)).containsExactly("maxWidgets");

        // 保存して読み戻しても、宣言（向きと名称）は行に残っている
        Threshold read = Threshold.parse(objectMapper, json(relaxed));
        Threshold readStandard = read.defaults().orElseThrow();
        assertThat(read.looserLimitsThan(readStandard)).containsExactly("maxWidgets");
        assertThat(read.limit("maxWidgets")).contains(new Threshold.Limit(Threshold.AT_MOST, "ウィジェットの上限"));
        assertThat(readStandard.extraValue("maxWidgets")).isEqualByComparingTo("3");

        // 下限として宣言すれば、下げたときに緩い
        assertThat(Threshold.atMost(0).withLimit("minWidgets", Threshold.AT_LEAST, "ウィジェットの下限", 1)
                .relaxedFrom(Threshold.atMost(0).withLimit("minWidgets", Threshold.AT_LEAST, "ウィジェットの下限", 2))
                .isRelaxed()).isTrue();
        // 宣言しない補足は、数でも比べない
        assertThat(Threshold.atMost(0).with("maxWidgets", 9)
                .relaxedFrom(Threshold.atMost(0).with("maxWidgets", 3)).isRelaxed()).isFalse();
    }

    @Test
    void 上限や下限の向きは二通りに限る() {
        assertThatThrownBy(() -> Threshold.atMost(0).withLimit("maxWidgets", Threshold.INCREASE, "ウィジェットの上限", 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 緩いと記録した合格ラインを読み戻せる() {
        Threshold relaxed = Threshold.atMost(0).withLimit("maxComplexity", Threshold.AT_MOST, COMPLEXITY, 20)
                .relaxedFrom(Threshold.atMost(0).withLimit("maxComplexity", Threshold.AT_MOST, COMPLEXITY, 15));

        // 緩い行にだけ、上限・下限の宣言を添える。既定の合格ライン（default）は従来の形のまま
        assertThat(json(relaxed)).isEqualTo("{\"operator\":\"<=\",\"value\":0,\"maxComplexity\":20,"
                + "\"relaxed\":true,\"default\":{\"operator\":\"<=\",\"value\":0,\"maxComplexity\":15},"
                + "\"limits\":{\"maxComplexity\":{\"operator\":\"<=\",\"label\":\"" + COMPLEXITY + "\"}}}");

        Threshold read = Threshold.parse(objectMapper, json(relaxed));

        assertThat(read.isRelaxed()).isTrue();
        Threshold standard = read.defaults().orElseThrow();
        assertThat(standard.extraValue("maxComplexity")).isEqualByComparingTo("15");
        assertThat(read.looserLimitsThan(standard)).containsExactly("maxComplexity");
        assertThat(json(read)).isEqualTo(json(relaxed));
        // 印の無い過去の行は緩くないものとして読む
        assertThat(Threshold.parse(objectMapper, "{\"operator\":\">=\",\"value\":60}").isRelaxed()).isFalse();
    }

    /** 上限・下限の宣言を保存する前の「緩い」行は、旧形式の表で向きと名称を補って読む。 */
    @Test
    void 宣言の無い過去の緩い行も読み戻せる() {
        Threshold read = Threshold.parse(objectMapper, "{\"operator\":\"<=\",\"value\":0,\"maxComplexity\":20,"
                + "\"relaxed\":true,\"default\":{\"operator\":\"<=\",\"value\":0,\"maxComplexity\":15}}");

        assertThat(read.looserLimitsThan(read.defaults().orElseThrow())).containsExactly("maxComplexity");
        assertThat(read.limit("maxComplexity")).contains(new Threshold.Limit(Threshold.AT_MOST, COMPLEXITY));
    }

    private String json(Threshold threshold) {
        return objectMapper.writeValueAsString(threshold.toMap());
    }
}
