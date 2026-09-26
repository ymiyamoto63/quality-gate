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
 */
@ConfigurationProperties(prefix = "quality-gate")
public record QualityGateProperties(
        Path artifactRoot,
        long maxArtifactBytes,
        long maxRunBytes,
        String baseUrl,
        List<String> ingestTokens) {

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
    }
}
