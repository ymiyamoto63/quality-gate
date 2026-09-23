package com.qualitygate.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * quality-gate 固有の設定。
 *
 * @param artifactRoot   成果物ストアのルートディレクトリ
 * @param maxArtifactBytes   1 ファイルあたりの上限
 * @param maxRunBytes        1 Run あたりの合計上限
 * @param baseUrl        通知や CI ログに出す自身の URL
 * @param notification   通知（メール）の設定
 */
@ConfigurationProperties(prefix = "quality-gate")
public record QualityGateProperties(
        Path artifactRoot,
        long maxArtifactBytes,
        long maxRunBytes,
        String baseUrl,
        Notification notification) {

    /** @param mailFrom メールの差出人 */
    public record Notification(String mailFrom) {

        public Notification {
            if (mailFrom == null || mailFrom.isBlank()) {
                mailFrom = "quality-gate@localhost";
            }
        }
    }

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
        if (notification == null) {
            notification = new Notification(null);
        }
    }
}
