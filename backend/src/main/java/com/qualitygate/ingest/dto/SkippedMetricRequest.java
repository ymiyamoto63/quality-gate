package com.qualitygate.ingest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "スキップの申告。申告のない未提出は ERROR として扱われる。")
public record SkippedMetricRequest(

        @Schema(example = "M-02")
        @NotBlank
        @Pattern(regexp = "^M-\\d{2}$", message = "M-01 のような形式で指定してください")
        String metricId,

        @Schema(description = "なぜ計測しなかったか。Run 詳細にそのまま表示される",
                example = "GitHub ホストランナーのため PIT を実行しない")
        @NotBlank
        String reason) {
}
