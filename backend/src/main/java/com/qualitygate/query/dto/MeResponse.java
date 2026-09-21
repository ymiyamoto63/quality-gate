package com.qualitygate.query.dto;

import com.qualitygate.domain.model.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "ログイン中の利用者。フロントエンドはこの role で表示を制御する（防御は API 側）。")
public record MeResponse(
        UUID userId,
        String githubLogin,
        String displayName,
        String avatarUrl,
        UserRole role) {
}
