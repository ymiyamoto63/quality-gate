package com.qualitygate.notify;

import com.qualitygate.domain.model.NotificationCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 通知設定（メールのみ）の入力と応答。 */
public final class NotificationSettingsDtos {

    private NotificationSettingsDtos() {
    }

    @Schema(description = "通知設定。通知はメールで送る")
    public record NotificationSettingsResponse(
            @NotNull NotificationCondition condition,
            @NotNull List<String> emailRecipients,
            @NotNull @Schema(description = "サーバに SMTP が設定されているか。false ならメールは届かない")
            boolean emailAvailable,
            @NotNull @Schema(nullable = true) Instant updatedAt) {
    }

    /** 省略した項目は変更しない。 */
    public record UpdateNotificationSettingsRequest(
            @Schema(nullable = true) NotificationCondition condition,
            @Schema(nullable = true) @Size(max = 20) List<@Email @Size(max = 254) String> emailRecipients) {
    }
}
