package com.qualitygate;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 実行中のアプリから OpenAPI 仕様を書き出す。
 *
 * <p>springdoc-openapi-maven-plugin を使う方式もあるが、プラグインはアプリの起動に
 * データベース接続を要し、そのための専用プロファイルやモック設定が必要になる。
 * Testcontainers は既に導入済みであり、実際の依存を揃えて起動できるため、
 * 専用の迂回路を用意せずに済む。
 *
 * <p>出力した {@code api/openapi.yml} は、フロントエンドの型生成と
 * M-09（破壊的変更の検出）の入力になる。CI では再生成して差分が無いことを検証する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AbstractIntegrationTest
class OpenApiExportIT {

    private static final Logger log = LoggerFactory.getLogger(OpenApiExportIT.class);
    private static final Path OUTPUT = Path.of("..", "api", "openapi.yml");

    @Value("${local.server.port}")
    int port;

    @Test
    void OpenAPI仕様を書き出す() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/v3/api-docs.yaml".formatted(port)))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        String yaml = response.body();

        assertThat(yaml).isNotBlank();
        assertThat(yaml).contains("openapi:");
        assertThat(yaml).contains("/api/v1/runs");
        assertThat(yaml).contains("/api/v1/dashboard");

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, yaml, StandardCharsets.UTF_8);
        log.info("OpenAPI 仕様を書き出しました: {}", OUTPUT.toAbsolutePath().normalize());
    }
}
