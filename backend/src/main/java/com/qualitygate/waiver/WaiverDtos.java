package com.qualitygate.waiver;

import com.qualitygate.domain.model.WaiverReasonCategory;
import com.qualitygate.domain.model.WaiverScope;
import com.qualitygate.domain.model.WaiverStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 免除（S-07）の入力と応答。 */
public final class WaiverDtos {

    private WaiverDtos() {
    }

    public record CreateWaiverRequest(
            @NotNull UUID repositoryId,
            @NotNull WaiverScope scope,
            @NotBlank @Pattern(regexp = "^M-\\d{2}$", message = "指標 ID（M-06 など）を指定してください")
            String metricId,
            @Schema(nullable = true, description = "scope=FINDING のとき必須。違反一覧の fingerprint")
            @Pattern(regexp = "^[0-9a-f]{64}$", message = "fingerprint は 64 桁の 16 進数です")
            String fingerprint,
            @NotNull WaiverReasonCategory reasonCategory,
            @NotBlank
            @Size(min = 20, max = 4000,
                    message = "理由は 20 文字以上で、根拠（回避策・参照先など）を含めて書いてください")
            String reason,
            @NotNull @Schema(description = "期限。現在より後、最大 90 日先まで") Instant expiresAt) {
    }

    @Schema(description = "免除。理由の全文を返す（折りたたむと判断の妥当性を検証できない）")
    public record WaiverItem(
            @NotNull UUID waiverId,
            @NotNull UUID repositoryId,
            @NotNull String repositoryFullName,
            @NotNull WaiverScope scope,
            @NotNull String metricId,
            @NotNull String metricName,
            @NotNull @Schema(nullable = true) String fingerprint,
            @NotNull @Schema(nullable = true, description = "登録時点の違反の見出し") String title,
            @NotNull WaiverReasonCategory reasonCategory,
            @NotNull String reasonCategoryLabel,
            @NotNull String reason,
            @NotNull WaiverStatus status,
            @NotNull @Schema(nullable = true) String createdByLogin,
            @NotNull Instant createdAt,
            @NotNull Instant expiresAt,
            @NotNull @Schema(description = "期限まで 7 日以内の有効な免除") boolean expiringSoon,
            @NotNull @Schema(nullable = true) Instant revokedAt,
            @NotNull @Schema(nullable = true) String revokedByLogin) {
    }

    public record WaiverList(
            @NotNull List<WaiverItem> items,
            @NotNull @Schema(description = "有効な免除の件数") long activeCount,
            @NotNull @Schema(description = "7 日以内に期限切れになる有効な免除の件数") long expiringSoonCount) {
    }
}
