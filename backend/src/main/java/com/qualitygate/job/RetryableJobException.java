package com.qualitygate.job;

/**
 * 再実行で回復しうる失敗（外部 API のタイムアウト、DB の一時エラーなど）。
 *
 * <p>形式不正のように再実行しても同じ結果になるものには使わない。
 */
public class RetryableJobException extends RuntimeException {

    public RetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
