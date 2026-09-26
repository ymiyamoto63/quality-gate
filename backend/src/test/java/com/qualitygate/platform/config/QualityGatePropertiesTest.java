package com.qualitygate.platform.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityGatePropertiesTest {

    @Test
    void 保持期間は指定が無ければ既定値になる() {
        QualityGateProperties properties = new QualityGateProperties(null, 0, 0, null, null, null);

        assertThat(properties.retention())
                .isEqualTo(new QualityGateProperties.Retention(730, 90, 730));
    }

    @Test
    void 保持期間は下限を下回ると起動しない() {
        // 誤って短い日数を設定すると、日次バッチが大半のデータを消す
        assertThatThrownBy(() -> new QualityGateProperties.Retention(7, 90, 730))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run-days");
        assertThatThrownBy(() -> new QualityGateProperties.Retention(730, 90, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audit-log-days");
        assertThat(new QualityGateProperties.Retention(30, 1, 365).artifactDays()).isEqualTo(1);
    }
}
