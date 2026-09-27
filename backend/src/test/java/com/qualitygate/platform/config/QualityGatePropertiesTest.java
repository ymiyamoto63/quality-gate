package com.qualitygate.platform.config;

import com.qualitygate.evaluate.GateThresholds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityGatePropertiesTest {

    private static final QualityGateProperties.Login LOGIN =
            new QualityGateProperties.Login(null, "test-login-password-0123");

    @Test
    void 計測対象のリポジトリが無ければ起動しない() {
        assertThatThrownBy(() -> new QualityGateProperties(null, null, 0, 0, null, null, LOGIN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QG_REPOSITORY");
        assertThatThrownBy(() -> new QualityGateProperties("like-chatgpt", null, 0, 0, null, null, LOGIN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/name");
    }

    @Test
    void ログインのパスワードが無いか短ければ起動しない() {
        assertThatThrownBy(() -> new QualityGateProperties.Login("quality", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QG_LOGIN_PASSWORD");
        assertThatThrownBy(() -> new QualityGateProperties.Login("quality", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12 文字以上");
        assertThat(LOGIN.username()).isEqualTo("quality");
    }

    @Test
    void 合格ラインは指定が無ければ既定値になる() {
        QualityGateProperties properties =
                new QualityGateProperties("ymiyamoto63/like-chatgpt", null, 0, 0, null, null, LOGIN, null);
        QualityGateProperties.Gate gate = properties.gate();

        assertThat(gate.branchCoverageMin()).isEqualByComparingTo("75");
        assertThat(gate.mutationScoreMin()).isEqualByComparingTo("60");
        assertThat(gate.responseTimeP95MaxMs()).isEqualByComparingTo("500");
        assertThat(gate.errorRateMaxPct()).isEqualByComparingTo("0.1");
        assertThat(gate.complexityMax()).isEqualTo(15);
        assertThat(gate.testSuccessRateMin()).isEqualByComparingTo("100");
        assertThat(GateThresholds.from(gate).enabledMetrics()).hasSize(12);
    }

    @Test
    void 無効にした指標は判定しない() {
        QualityGateProperties.Gate gate = new QualityGateProperties.Gate(List.of("M-03", " M-04 ", ""), null,
                new BigDecimal("80"), null, null, null, null, null, null, null, null, null, null, null, null, null,
                0, null, null, null);

        GateThresholds thresholds = GateThresholds.from(gate);
        assertThat(thresholds.enabledMetrics()).doesNotContain("M-03", "M-04").hasSize(10);
        assertThat(thresholds.branchCoverageThreshold()).isEqualByComparingTo("80");
        // 0 件のテストの合格は「検証していない」の言い換えにすぎない
        assertThat(thresholds.testResults().minTestCount()).isEqualTo(1);
    }

    @Test
    void 知らない指標IDは起動時に拒否する() {
        assertThatThrownBy(() -> new QualityGateProperties.Gate(List.of("M3"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QG_DISABLED_METRICS");
    }

}
