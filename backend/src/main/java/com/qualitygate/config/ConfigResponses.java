package com.qualitygate.config;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 設定（S-06）の応答。 */
public final class ConfigResponses {

    private ConfigResponses() {
    }

    @Schema(description = "直近の Run に送られた合格ラインと、その検証結果。版の履歴は Git で見る")
    public record RepositoryConfig(
            @NotNull @Schema(nullable = true, description = "直近の Run。まだ計測が無ければ null") UUID runId,
            @NotNull @Schema(nullable = true) Instant measuredAt,
            @NotNull @Schema(nullable = true,
                    description = "合格ラインを送った quality-gate リポジトリのコミット") String configCommitSha,
            @NotNull @Schema(nullable = true,
                    description = "設定ファイルの内容。設定ファイルの無い Run（既定値で判定）では null") String rawYaml,
            @NotNull @Schema(description = "検証エラー。不正なら判定されず、Run は処理失敗になる")
            List<ValidationErrorItem> errors) {
    }

    public record ValidationErrorItem(
            @NotNull @Schema(nullable = true, description = "1 始まりの行番号") Integer line,
            @NotNull String path,
            @NotNull String message) {
    }
}
