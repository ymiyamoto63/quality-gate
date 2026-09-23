package com.qualitygate.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 設定（S-06）。閲覧は全員、UI からの編集は管理者のみ。 */
@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/config")
@Tag(name = "Config", description = ".quality-gate.yml の表示・検証結果・版履歴")
public class ConfigController {

    private final ConfigQueryService service;

    public ConfigController(ConfigQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "現在の設定と版の履歴、直近の検証結果を取得する",
            description = "検証エラーは行番号とキーのパス付きで返す。書いた人が自力で直せるように。")
    public ConfigResponses.RepositoryConfig get(@PathVariable UUID repositoryId) {
        return service.get(repositoryId);
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "UI から設定を更新する",
            description = "CI が送ったファイルが優先される。直近に判定された Run がファイルの設定で判定されている場合は "
                    + "409 CONFIG_MANAGED_BY_FILE。検証エラーは 422 CONFIG_VALIDATION_FAILED（行番号付き）。")
    public ConfigResponses.RepositoryConfig update(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody ConfigResponses.UpdateConfigRequest request) {
        service.update(repositoryId, request.rawYaml());
        return service.get(repositoryId);
    }
}
