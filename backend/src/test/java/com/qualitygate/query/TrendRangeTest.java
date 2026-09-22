package com.qualitygate.query;

import com.qualitygate.platform.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrendRangeTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Test
    void 省略時は直近30日にする() {
        TrendQueryService.Range range = TrendQueryService.Range.of(null, NOW);

        assertThat(range.to()).isEqualTo(NOW);
        assertThat(Duration.between(range.from(), range.to()))
                .isEqualTo(TrendQueryService.DEFAULT_RANGE);
    }

    @Test
    void 開始が終了より後なら拒否する() {
        Instant later = NOW.plus(Duration.ofDays(1));

        assertThatThrownBy(() -> TrendQueryService.Range.of(later, NOW))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("from は to より前");
    }

    @Test
    void 開始と終了が同じでも拒否する() {
        assertThatThrownBy(() -> TrendQueryService.Range.of(NOW, NOW))
                .isInstanceOf(ApiException.class);
    }

    /** 上限を設けるのは、点が増えすぎて応答時間が崩れるのを防ぐため（FR-08-1）。 */
    @Test
    void 上限を超える期間は拒否する() {
        Instant tooEarly = NOW.minus(TrendQueryService.MAX_RANGE).minus(Duration.ofDays(1));

        assertThatThrownBy(() -> TrendQueryService.Range.of(tooEarly, NOW))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("730 日");
    }

    @Test
    void 上限ちょうどは受け付ける() {
        Instant earliest = NOW.minus(TrendQueryService.MAX_RANGE);

        assertThat(TrendQueryService.Range.of(earliest, NOW).from()).isEqualTo(earliest);
    }
}
