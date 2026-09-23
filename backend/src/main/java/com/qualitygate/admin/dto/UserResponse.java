package com.qualitygate.admin.dto;

import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "許可リストの利用者。ここに無い GitHub ユーザーはログインできない")
public record UserResponse(
        @NotNull UUID userId,
        @NotNull String githubLogin,
        @NotNull @Schema(nullable = true, description = "初回ログインまでは null") String displayName,
        @NotNull @Schema(nullable = true) String avatarUrl,
        @NotNull UserRole role,
        @NotNull UserStatus status,
        @NotNull Instant createdAt,
        @NotNull @Schema(nullable = true, description = "一度もログインしていなければ null")
        Instant lastLoginAt) {

    public static UserResponse of(UserAccount user) {
        return new UserResponse(user.getId(), user.getGithubLogin(), user.getDisplayName(),
                user.getAvatarUrl(), user.getRole(), user.getStatus(), user.getCreatedAt(),
                user.getLastLoginAt());
    }

    @Schema(description = "利用者の一覧")
    public record UserList(@NotNull List<UserResponse> items) {
    }
}
