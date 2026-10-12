package com.qualitygate.release;

import com.qualitygate.domain.metric.Threshold;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdTextTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 緩い上限は判定器が宣言した名称と既定値で示す() {
        Threshold relaxed = Threshold.atMost(0).withLimit("maxWidgets", Threshold.AT_MOST, "ウィジェットの上限", 9)
                .relaxedFrom(Threshold.atMost(0).withLimit("maxWidgets", Threshold.AT_MOST, "ウィジェットの上限", 3));

        Threshold read = Threshold.parse(objectMapper, objectMapper.writeValueAsString(relaxed.toMap()));

        assertThat(ThresholdText.defaultOf(read, "count")).isEqualTo("ウィジェットの上限 3");
    }

    @Test
    void 宣言を保存する前の行も名称を補って示す() {
        Threshold read = Threshold.parse(objectMapper, "{\"operator\":\"<=\",\"value\":0,\"maxComplexity\":20,"
                + "\"relaxed\":true,\"default\":{\"operator\":\"<=\",\"value\":0,\"maxComplexity\":15}}");

        assertThat(ThresholdText.defaultOf(read, "count")).isEqualTo("関数ごとの循環的複雑度の上限 15");
    }

    @Test
    void 値そのものが緩ければ既定の合格ラインを示す() {
        Threshold relaxed = Threshold.atLeast(new BigDecimal("70"))
                .relaxedFrom(Threshold.atLeast(new BigDecimal("75")));

        assertThat(ThresholdText.defaultOf(relaxed, "percent")).isEqualTo("≥ 75%");
        assertThat(ThresholdText.defaultOf(Threshold.atLeast(new BigDecimal("75")), "percent")).isNull();
    }
}
