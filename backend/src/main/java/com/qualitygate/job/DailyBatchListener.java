package com.qualitygate.job;

import java.time.Instant;

/**
 * 日次バッチの節目で呼ばれる。通知（期限接近・計測途絶）がここに接続する。
 *
 * <p>ジョブの側が通知の実装を知らずに済むよう、逆向きの依存を避ける差し込み口にしている。
 */
public interface DailyBatchListener {

    /** 免除の期限切れ処理の後。期限が近い免除を知らせる。 */
    default void onWaiversChecked(Instant now) {
    }

    /** 鮮度の確認（{@code CHECK_FRESHNESS}）。計測途絶・完全計測途絶を知らせる。 */
    default void onFreshnessCheck(Instant now) {
    }
}
