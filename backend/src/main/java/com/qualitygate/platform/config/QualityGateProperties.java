package com.qualitygate.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * quality-gate 固有の設定。
 *
 * @param artifactRoot   成果物ストアのルートディレクトリ
 * @param maxArtifactBytes   1 ファイルあたりの上限
 * @param maxRunBytes        1 Run あたりの合計上限
 * @param baseUrl        CI ログに出す自身の URL
 * @param ingestTokens   取り込み API の Ingest Token（{@code QG_INGEST_TOKEN}。カンマ区切りで複数。交換のときだけ新旧を並べる）。
 *                       空なら取り込み API はすべて 401 を返す
 * @param retention      データ保持期間（FR-13-1）
 */
@ConfigurationProperties(prefix = "quality-gate")
public record QualityGateProperties(
        Path artifactRoot,
        long maxArtifactBytes,
        long maxRunBytes,
        String baseUrl,
        List<String> ingestTokens,
        Retention retention) {

    public QualityGateProperties {
        if (artifactRoot == null) {
            artifactRoot = Path.of("./data/artifacts");
        }
        if (maxArtifactBytes <= 0) {
            maxArtifactBytes = 50L * 1024 * 1024;
        }
        if (maxRunBytes <= 0) {
            maxRunBytes = 200L * 1024 * 1024;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        ingestTokens = ingestTokens == null ? List.of() : List.copyOf(ingestTokens);
        if (retention == null) {
            retention = new Retention(0, 0, 0);
        }
    }

    /**
     * データ保持期間（日。docs/spec/06-database-design.md 7 章）。0 以下なら既定値。
     *
     * <p>下限を設けるのは、誤って短い日数を設定すると日次バッチが大半のデータを消すため。下限を下回れば起動しない。
     *
     * @param runDays      Run・指標値・違反。既定 730 日（2 年）、下限 30 日
     * @param artifactDays 成果物のファイル実体。既定 90 日、下限 1 日
     * @param auditLogDays 監査ログ。既定 730 日、下限 365 日
     */
    public record Retention(int runDays, int artifactDays, int auditLogDays) {

        public Retention {
            runDays = orDefault(runDays, 730, 30, "run-days");
            artifactDays = orDefault(artifactDays, 90, 1, "artifact-days");
            auditLogDays = orDefault(auditLogDays, 730, 365, "audit-log-days");
        }

        private static int orDefault(int days, int defaultDays, int minDays, String name) {
            if (days <= 0) {
                return defaultDays;
            }
            if (days < minDays) {
                throw new IllegalArgumentException(
                        "quality-gate.retention.%s は %d 日以上にしてください（設定値: %d）".formatted(name, minDays, days));
            }
            return days;
        }
    }
}
