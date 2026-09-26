package com.qualitygate.ingest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

@Schema(description = "Run の作成要求。CI が計測開始時に送信する。")
public record CreateRunRequest(

        @Schema(description = "owner/name 形式。Ingest Token の発行元と一致する必要がある",
                example = "ymiyamoto63/quality-gate")
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$",
                message = "owner/name の形式で指定してください")
        String repository,

        @Schema(description = "計測対象のコミット SHA（40 桁）")
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{40}$", message = "40 桁の 16 進数で指定してください")
        String commitSha,

        @Schema(description = "差分計測の比較基準。省略時は quality-gate が merge-base を解決する")
        @Pattern(regexp = "^[0-9a-f]{40}$", message = "40 桁の 16 進数で指定してください")
        String baseCommitSha,

        @NotBlank
        String branch,

        Integer pullRequestNumber,

        @NotBlank
        String triggeredBy,

        String ciRunUrl,

        @NotNull
        Instant measuredAt,

        @Schema(description = "CI が実行しなかった指標の申告。省略時は全指標を計測したものとして扱う")
        @Valid
        List<SkippedMetricRequest> skippedMetrics) {

    public List<SkippedMetricRequest> skippedMetricsOrEmpty() {
        return skippedMetrics == null ? List.of() : skippedMetrics;
    }

    public String owner() {
        return repository.substring(0, repository.indexOf('/'));
    }

    public String name() {
        return repository.substring(repository.indexOf('/') + 1);
    }
}
