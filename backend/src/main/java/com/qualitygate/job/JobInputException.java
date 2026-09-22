package com.qualitygate.job;

/**
 * ジョブの入力そのものが誤っている（設定の検証エラー、成果物の形式不正など）。
 *
 * <p>再実行しても同じ結果になるため、試行回数を使い切らずに打ち切る。
 * リトライすれば直るものと入力が誤っているものを区別せずに再試行すると、
 * 失敗の原因が試行回数分のログに埋もれて見えなくなる。
 */
public class JobInputException extends RuntimeException {

    public JobInputException(String message) {
        super(message);
    }

    public JobInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
