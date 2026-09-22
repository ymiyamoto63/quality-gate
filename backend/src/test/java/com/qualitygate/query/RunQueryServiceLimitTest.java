package com.qualitygate.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunQueryServiceLimitTest {

    @Test
    void 指定が無ければ既定件数にする() {
        assertThat(RunQueryService.normalizeLimit(0)).isEqualTo(RunQueryService.DEFAULT_LIMIT);
    }

    /** 上限を設けるのは、1 リクエストで全件取得されて応答時間が崩れるのを防ぐため。 */
    @Test
    void 上限を超える指定は上限に丸める() {
        assertThat(RunQueryService.normalizeLimit(10_000))
                .isEqualTo(RunQueryService.MAX_LIMIT);
    }

    @Test
    void 負の指定でも既定件数にする() {
        assertThat(RunQueryService.normalizeLimit(-1)).isEqualTo(RunQueryService.DEFAULT_LIMIT);
    }
}
