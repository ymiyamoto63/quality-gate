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

    private String json(Threshold threshold) {
        return objectMapper.writeValueAsString(threshold.toMap());
    }
}
