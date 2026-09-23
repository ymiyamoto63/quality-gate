package com.qualitygate.admin;

import com.qualitygate.platform.security.CurrentUser;
import com.qualitygate.platform.settings.RetentionSettings;
import com.qualitygate.platform.settings.SystemSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** システム設定（S-09 保持期間）。管理者のみ。 */
@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Settings", description = "保持期間などのシステム設定（管理者のみ）")
public class SettingsController {

    private final SystemSettingsService settings;
    private final CurrentUser currentUser;

    public SettingsController(SystemSettingsService settings, CurrentUser currentUser) {
        this.settings = settings;
        this.currentUser = currentUser;
    }

    @GetMapping("/retention")
    @Operation(summary = "データ保持期間を取得する")
    public RetentionSettings retention() {
        return settings.retention();
    }

    @PutMapping("/retention")
    @Operation(summary = "データ保持期間を変更する",
            description = "翌日の保持期間バッチから効く。短くすると古いデータが削除される。")
    public RetentionSettings updateRetention(@Valid @RequestBody RetentionSettings request) {
        return settings.updateRetention(currentUser.actor(), request);
    }
}
