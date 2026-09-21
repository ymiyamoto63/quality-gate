package com.qualitygate.platform.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Uuid7Test {

    @Test
    void バージョン7とRFC4122バリアントを持つ() {
        UUID id = Uuid7.generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void 埋め込まれたタイムスタンプを取り出せる() {
        long epochMillis = 1_800_000_000_000L;

        UUID id = Uuid7.generate(epochMillis);

        assertThat(Uuid7.timestampOf(id)).isEqualTo(epochMillis);
    }

    @Test
    void 時刻が進むと値も大きくなる() {
        UUID earlier = Uuid7.generate(1_000L);
        UUID later = Uuid7.generate(2_000L);

        // インデックスの局所性はこの順序性に依存している
        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void 同一ミリ秒でも衝突しない() {
        Set<UUID> generated = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            generated.add(Uuid7.generate(1_234L));
        }

        assertThat(generated).hasSize(10_000);
    }

    @Test
    void バージョン7でないUUIDからは時刻を取り出せない() {
        UUID v4 = UUID.randomUUID();

        assertThatThrownBy(() -> Uuid7.timestampOf(v4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv7 ではありません");
    }
}
