package com.qualitygate.platform.web;

import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageCursorTest {

    @Test
    void キーセットのカーソルを往復できる() {
        Instant measuredAt = Instant.parse("2026-09-22T02:10:00Z");
        UUID id = UUID.randomUUID();

        PageCursor.Keyset decoded = PageCursor.toKeyset(PageCursor.ofKeyset(measuredAt, id));

        assertThat(decoded.measuredAt()).isEqualTo(measuredAt);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void 位置のカーソルを往復できる() {
        assertThat(PageCursor.toOffset(PageCursor.ofOffset(40))).isEqualTo(40);
    }

    /** 中身が見えると、クライアントが算術を始めて方式変更で壊れる。 */
    @Test
    void カーソルは中身が読めない形で渡す() {
        assertThat(PageCursor.ofOffset(40)).doesNotContain("40");
    }

    @Test
    void 種類の違うカーソルは拒否する() {
        String keyset = PageCursor.ofKeyset(Instant.now(), UUID.randomUUID());

        assertThatThrownBy(() -> PageCursor.toOffset(keyset))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PageCursor.toKeyset(PageCursor.ofOffset(1)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 壊れたカーソルはサーバエラーではなくリクエスト誤りとして返す() {
        assertThatThrownBy(() -> PageCursor.toOffset("!!!not-base64!!!"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 負の位置は受け付けない() {
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("o:-5".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PageCursor.toOffset(forged))
                .isInstanceOf(ApiException.class);
    }
}
