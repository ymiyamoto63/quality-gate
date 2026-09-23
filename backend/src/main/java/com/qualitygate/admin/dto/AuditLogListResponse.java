package com.qualitygate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "監査ログ。新しいものから返す")
public record AuditLogListResponse(
        @NotNull List<AuditLogItem> items,
        @NotNull @Schema(nullable = true) String nextCursor,
        @NotNull boolean hasMore) {

    public record AuditLogItem(
            @NotNull UUID auditLogId,
            @NotNull Instant occurredAt,
            @NotNull @Schema(nullable = true, description = "日次バッチの操作は system") String actorLogin,
            @NotNull String action,
            @NotNull String targetType,
            @NotNull @Schema(nullable = true) String targetId,
            @NotNull @Schema(nullable = true, description = "変更前の値") Map<String, Object> before,
            @NotNull @Schema(nullable = true, description = "変更後の値") Map<String, Object> after) {
    }
}
