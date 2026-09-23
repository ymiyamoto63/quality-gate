package com.qualitygate.notify;

/**
 * 1 通の送信結果。
 *
 * @param sent      送れたか
 * @param error     送れなかった理由。送れた場合は null
 * @param retryable 再実行で回復しうる失敗か（SMTP サーバの一時的な障害）
 */
public record ChannelResult(boolean sent, String error, boolean retryable) {

    public static ChannelResult ok() {
        return new ChannelResult(true, null, false);
    }

    public static ChannelResult failed(String error, boolean retryable) {
        return new ChannelResult(false, error, retryable);
    }
}
