package com.qualitygate.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemovedSettingsTest {

    @Test
    void 廃止したスキップの増加の上限が残っていれば起動しない() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("QG_SKIPPED_TESTS_INCREASE_MAX", "3");

        assertThatThrownBy(() -> new RemovedSettings(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QG_SKIPPED_TESTS_INCREASE_MAX は廃止しました")
                .hasMessageContaining("QG_SKIPPED_TESTS_MAX")
                .hasMessageContaining("設定値: 3");
    }

    @Test
    void 空なら廃止した環境変数は無いものとして起動する() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("QG_SKIPPED_TESTS_INCREASE_MAX", "")
                .withProperty("QG_SKIPPED_TESTS_MAX", "3");

        assertThatCode(() -> new RemovedSettings(environment)).doesNotThrowAnyException();
    }
}
