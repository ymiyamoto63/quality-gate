package com.qualitygate.admin.dto;

import com.qualitygate.domain.model.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 許可リストへの追加。
 *
 * <p>ログイン名の実在は確かめない。GitHub API を呼ぶと外部の障害に巻き込まれるうえ、
 * 実在しない名前を登録しても、その名前ではログインできないだけで害がない。
 */
public record CreateUserRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$",
                message = "GitHub のログイン名（英数字とハイフン、39 文字以内）を指定してください")
        String githubLogin,
        @Schema(nullable = true, description = "省略時は VIEWER") UserRole role) {
}
