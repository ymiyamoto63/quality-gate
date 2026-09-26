package com.qualitygate.platform.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * データ保持期間（FR-17-1 / docs/spec/06-database-design.md 7 章）。
 *
 * <p>下限を設けるのは、誤って 0 日などを保存すると日次バッチが全データを消すため。
 */
@Schema(description = "データ保持期間（日）")
public record RetentionSettings(
        @NotNull @Min(30) @Max(3650)
        @Schema(description = "Run・指標値・違反。既定 730 日（2 年）") Integer runDays,
        @NotNull @Min(1) @Max(3650)
        @Schema(description = "成果物のファイル実体。既定 90 日") Integer artifactDays,
        @NotNull @Min(365) @Max(3650)
        @Schema(description = "監査ログ。既定 730 日。削除は管理ロールのバッチが行う") Integer auditLogDays) {

    public static final RetentionSettings DEFAULTS = new RetentionSettings(730, 90, 730);
}
