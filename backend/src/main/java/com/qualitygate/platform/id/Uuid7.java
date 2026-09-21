package com.qualitygate.platform.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUID version 7（RFC 9562）を採番する。
 *
 * <p>主キーに v7 を用いるのは、API に露出する ID を推測不能にしつつ、
 * 先頭 48 ビットがミリ秒精度のタイムスタンプであることでインデックスの
 * 局所性を保つためである。完全にランダムな v4 では、行を挿入するたびに
 * B-Tree の広い範囲が書き換わる。
 *
 * <p>同一ミリ秒内での単調増加は保証しない。本システムの書き込み頻度
 * （1 日あたり数十 Run）では、同一ミリ秒に複数行が生まれても
 * インデックスの局所性に影響しないためである。
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC4122 = 0x8000_0000_0000_0000L;

    private Uuid7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    static UUID generate(long epochMillis) {
        long randA = RANDOM.nextInt(0x1000);
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16 | VERSION_7 | randA;
        long lsb = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL | VARIANT_RFC4122;
        return new UUID(msb, lsb);
    }

    /** UUID に埋め込まれたタイムスタンプ（ミリ秒）を取り出す。テストと調査のために公開する。 */
    public static long timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("UUIDv7 ではありません: version=" + uuid.version());
        }
        return uuid.getMostSignificantBits() >>> 16 & 0xFFFF_FFFF_FFFFL;
    }
}
