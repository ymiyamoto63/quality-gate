package com.qualitygate.ingest;

import com.qualitygate.ingest.dto.CollectorSettingsResponse;
import com.qualitygate.platform.config.QualityGateProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 収集ランナーが計測の前に読む設定。認証は取り込み API と同じ Ingest Token。
 *
 * <p>判定しない指標（{@code QG_DISABLED_METRICS}）をアプリの設定だけで持てるよう、収集ランナーはここから読んで計測を止める。
 */
@RestController
@Tag(name = "Ingest", description = "収集ランナーからの計測結果の取り込み")
public class CollectorSettingsController {

    private final QualityGateProperties properties;

    public CollectorSettingsController(QualityGateProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/v1/collector/settings")
    @Operation(summary = "収集ランナーの設定を返す",
            description = "計測対象のリポジトリと判定しない指標。収集ランナーは判定しない指標を計測しない。")
    public CollectorSettingsResponse collectorSettings() {
        return new CollectorSettingsResponse(properties.repository(),
                properties.gate().disabledMetrics().stream().sorted().toList());
    }
}
