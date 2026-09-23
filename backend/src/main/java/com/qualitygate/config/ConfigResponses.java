package com.qualitygate.config;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 設定（S-06）の応答と入力。 */
public final class ConfigResponses {

    private ConfigResponses() {
    }

    @Schema(description = "現在の設定と版の履歴。validation は直近に届いた設定ファイルの検証結果")
    public record RepositoryConfig(
            @NotNull @Schema(nullable = true, description = "設定版が 1 つも無ければ null（既定値で判定）")
            ConfigVersion current,
            @NotNull List<ConfigHistoryItem> history,
            @NotNull ConfigValidation validation,
            @NotNull @Schema(description = "UI から編集できるか。直近に判定された Run が .quality-gate.yml の設定で判定されていれば false")
            boolean editable,
            @NotNull @Schema(description = "既定値の YAML。設定版が無いときの表示と UI 編集の初期値")
            String defaultYaml) {
    }

    public record ConfigVersion(
            @NotNull UUID gateConfigId,
            @NotNull int version,
            @NotNull String sourceType,
            @NotNull @Schema(nullable = true) String sourceCommitSha,
            @NotNull Instant createdAt,
            @NotNull String rawYaml,
            @NotNull Map<String, Object> parsed) {
    }

    public record ConfigHistoryItem(
            @NotNull UUID gateConfigId,
            @NotNull int version,
            @NotNull String sourceType,
            @NotNull @Schema(nullable = true) String sourceCommitSha,
            @NotNull Instant createdAt) {
    }

    @Schema(description = "設定ファイルの検証結果。不正なら判定されず、Run は処理失敗になる")
    public record ConfigValidation(
            @NotNull boolean valid,
            @NotNull List<ValidationErrorItem> errors,
            @NotNull @Schema(nullable = true, description = "検証に失敗した Run") UUID runId,
            @NotNull @Schema(nullable = true, description = "検証に失敗した設定ファイルの内容") String rawYaml) {
    }

    public record ValidationErrorItem(
            @NotNull @Schema(nullable = true, description = "1 始まりの行番号") Integer line,
            @NotNull String path,
            @NotNull String message) {
    }

    public record UpdateConfigRequest(
            @NotBlank @Size(max = 262144) String rawYaml) {
    }
}
