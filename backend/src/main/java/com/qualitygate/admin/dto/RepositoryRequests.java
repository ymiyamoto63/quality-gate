package com.qualitygate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** リポジトリ管理（S-08）の入力。 */
public final class RepositoryRequests {

    private RepositoryRequests() {
    }

    public record CreateRepositoryRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$",
                    message = "GitHub の owner（英数字とハイフン、39 文字以内）を指定してください")
            String owner,
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9._-]{1,100}$",
                    message = "リポジトリ名（英数字と . _ -、100 文字以内）を指定してください")
            String name,
            @Schema(nullable = true, description = "省略時は main") @Size(max = 255)
            String defaultBranch) {
    }

    /** 省略した項目は変更しない。 */
    public record UpdateRepositoryRequest(
            @Schema(nullable = true) @Size(min = 1, max = 255) String defaultBranch,
            @Schema(nullable = true, description = "false で無効化（ダッシュボードから外れ、取り込みも拒否される）")
            Boolean enabled) {
    }

    public record DefineComponentRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$",
                    message = "コンポーネント名（英数字と . _ -、64 文字以内）を指定してください")
            String name,
            @NotBlank @Size(max = 32) String language,
            @NotEmpty List<@NotBlank String> pathPatterns) {
    }

    public record IssueTokenRequest(
            @Schema(nullable = true, description = "用途のメモ（例: GitHub Actions）") @Size(max = 255)
            String description) {
    }
}
