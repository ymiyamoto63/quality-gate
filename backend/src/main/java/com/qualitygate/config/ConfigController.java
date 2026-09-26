package com.qualitygate.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 設定（S-06）。表示のみ。設定は collector/targets/*.gate.yml を Git で管理する（D-20）。 */
@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/config")
@Tag(name = "Config", description = "設定の表示・検証結果・版履歴")
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
}
