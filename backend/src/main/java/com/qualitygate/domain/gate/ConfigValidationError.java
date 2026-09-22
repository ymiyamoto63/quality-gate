package com.qualitygate.domain.gate;

/**
 * 設定の検証エラー。
 *
 * @param line    1 始まりの行番号。位置が特定できない場合は null
 * @param path    エラーの位置（{@code metrics.branch_coverage.threshold}）
 * @param message 何をどう直せばよいか。書いた人が自力で直せる情報とセットで返す
 */
public record ConfigValidationError(Integer line, String path, String message) {

    public static ConfigValidationError at(Integer line, String path, String message) {
        return new ConfigValidationError(line, path, message);
    }
}
