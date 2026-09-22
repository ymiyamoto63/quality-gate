package com.qualitygate.adapter;

/**
 * 成果物を解釈できなかった。
 *
 * <p>再実行しても同じ結果になるため、ジョブのリトライ対象にしない。
 * メッセージには「何がどこで期待と違ったか」を書く。CI のログにしか残らない場合があるため。
 */
public class ArtifactFormatException extends RuntimeException {

    public ArtifactFormatException(String message) {
        super(message);
    }

    public ArtifactFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
