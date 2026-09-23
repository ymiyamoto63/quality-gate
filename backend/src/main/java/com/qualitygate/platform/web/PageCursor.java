package com.qualitygate.platform.web;

import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * ページングのカーソル。
 *
 * <p>中身を不透明な文字列として渡すのは、送り方を後から変えられるようにするため。
 * クライアントが「これはオフセットだ」と解釈して算術を始めると、
 * キーセット方式に切り替えた時点で壊れる。
 */
public final class PageCursor {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private PageCursor() {
    }

    /**
     * 位置指定のカーソル。
     *
     * <p>Run は時系列に増え続けるため、オフセットではページ送りの途中で
     * 新しい Run が入ると重複・欠落が起きる（docs/initial/07-api-design.md 1.4）。
     * 並び順の鍵（{@code measuredAt}, {@code id}）そのものを持たせる。
     */
    public static String ofKeyset(Instant measuredAt, UUID id) {
        return encode("k:" + measuredAt.toString() + ":" + id);
    }

    public static Keyset toKeyset(String cursor) {
        String decoded = decode(cursor);
        if (!decoded.startsWith("k:")) {
            throw malformed();
        }
        int separator = decoded.lastIndexOf(':');
        try {
            return new Keyset(Instant.parse(decoded.substring(2, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw malformed();
        }
    }

    /**
     * 件数指定のカーソル。
     *
     * <p>Run に属する違反は判定時に確定したスナップショットであり、ページを
     * 送っている間に増減しない。増え続ける集合ではないため、オフセットでも
     * 重複・欠落は起きない。形式だけ揃えておき、必要になればキーセットへ移せる。
     */
    public static String ofOffset(int offset) {
        return encode("o:" + offset);
    }

    public static int toOffset(String cursor) {
        String decoded = decode(cursor);
        if (!decoded.startsWith("o:")) {
            throw malformed();
        }
        try {
            int offset = Integer.parseInt(decoded.substring(2));
            if (offset < 0) {
                throw malformed();
            }
            return offset;
        } catch (NumberFormatException e) {
            throw malformed();
        }
    }

    private static String encode(String raw) {
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        try {
            return new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw malformed();
        }
    }

    /**
     * 壊れたカーソルは 400 で返す。500 にしないのは、原因がリクエスト側にあり、
     * サーバの異常として警報を上げる対象ではないため。
     */
    private static ApiException malformed() {
        return new ApiException(ErrorCode.VALIDATION_FAILED,
                "cursor の形式が不正です。前の応答が返した nextCursor をそのまま渡してください");
    }

    public record Keyset(Instant measuredAt, UUID id) {
    }
}
