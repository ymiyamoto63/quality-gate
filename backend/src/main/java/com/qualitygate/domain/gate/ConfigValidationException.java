package com.qualitygate.domain.gate;

import java.util.List;

/**
 * 設定が不正で取り込めない。
 *
 * <p>再実行しても同じ結果になるため、ジョブのリトライ対象にしない。
 */
public class ConfigValidationException extends RuntimeException {

    private final transient List<ConfigValidationError> errors;

    public ConfigValidationException(List<ConfigValidationError> errors) {
        super(errors.stream()
                .map(e -> "%s: %s".formatted(e.path(), e.message()))
                .reduce((a, b) -> a + " / " + b)
                .orElse("設定の検証に失敗しました"));
        this.errors = List.copyOf(errors);
    }

    public List<ConfigValidationError> errors() {
        return errors;
    }
}
