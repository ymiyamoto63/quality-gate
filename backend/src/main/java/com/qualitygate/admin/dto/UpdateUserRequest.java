package com.qualitygate.admin.dto;

import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** ロールの変更・無効化。省略した項目は変更しない。 */
public record UpdateUserRequest(
        @Schema(nullable = true) UserRole role,
        @Schema(nullable = true) UserStatus status) {
}
