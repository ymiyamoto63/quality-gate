package com.qualitygate.evaluate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GateThresholdsTest {

    @Test
    void 既定の合格ラインにはこの設定の対象をそのまま組み合わせる() {
        GateThresholds configured = EvaluatorTestSupport.thresholdsWith(Map.of(
                "disabled-metrics", List.of("M-03"),
                "exclusions", List.of("**/Main.java"),
                "mutation-components", List.of("backend"),
                "perf-scenarios", List.of("checkout"),
                "accessibility-pages", List.of("/settings"),
                "branch-coverage-min", 60,
                "complexity-max", 30));

        GateThresholds standard = configured.withDefaultLimits();

        assertThat(standard.scope()).isEqualTo(configured.scope());
        assertThat(standard.limits()).isEqualTo(GateThresholds.defaults().limits());
        assertThat(standard.limits().coverage().branchMin()).isEqualByComparingTo("75");
        assertThat(standard.limits().maxComplexity()).isEqualTo(15);
    }

    @Test
    void 既定の設定は値だけ既定にしても変わらない() {
        assertThat(GateThresholds.defaults().withDefaultLimits()).isEqualTo(GateThresholds.defaults());
    }
}
