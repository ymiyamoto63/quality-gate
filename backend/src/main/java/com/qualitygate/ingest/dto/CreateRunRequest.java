package com.qualitygate.ingest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

        @Schema(description = "差分計測の比較基準（収集ランナーが求めて送る）。省略すると比較元なしで判定する")
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

        @Schema(description = "計測したコミットを指すタグ（収集ランナーが対象リポジトリの履歴から求める）。"
                + "リリース判定でタグをコミットに解決するのに使う")
        @Size(max = 100)
        List<@Size(max = 255) @Pattern(regexp = TAG, message = "タグ名として使えない文字を含んでいます") String> tags,

        @Schema(description = "CI が実行しなかった指標の申告。省略時は全指標を計測したものとして扱う")
        @Valid
        List<SkippedMetricRequest> skippedMetrics) {

    /** git のタグ名に使えない文字（空白・制御文字・{@code ~^:?*[\}）と {@code ..}、先頭と末尾の {@code /} を拒否する。 */
    static final String TAG = "^(?!/)(?!.*\\.\\.)(?!.*/$)[^\\s\\p{Cntrl}~^:?*\\[\\\\]+$";

    public List<String> tagsOrEmpty() {
        return tags == null ? List.of() : List.copyOf(tags);
    }

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
