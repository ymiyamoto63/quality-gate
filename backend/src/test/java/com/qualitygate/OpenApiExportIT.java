package com.qualitygate;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.yaml.snakeyaml.Yaml;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
 * M-08（破壊的変更の検出）の入力になる。CI では再生成して差分が無いことを検証する。
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

    /**
     * 入れ子レコードのスキーマ名が衝突していないことを確かめる。
     *
     * <p>springdoc はスキーマ名に Java の単純名を使うため、別の応答に同じ名前の
     * 入れ子レコード（どちらも {@code Item} など）があると、片方の定義がもう片方を
     * 上書きする。<strong>この事故は静かに起きる</strong>。仕様は生成できて、
     * 型も生成できて、ただ中身が別の型になる。実際に
     * {@code RunListResponse.Item} と {@code FindingListResponse.Item} で起きた。
     */
    @Test
    void 一覧応答が別々の要素スキーマを指している() throws Exception {
        Map<String, Object> schemas = schemasOf(exportedSpec());

        assertThat(itemRefOf(schemas, "RunListResponse", "items")).isEqualTo("RunSummary");
        assertThat(itemRefOf(schemas, "FindingListResponse", "items")).isEqualTo("FindingItem");
        assertThat(itemRefOf(schemas, "TrendResponse", "series")).isEqualTo("TrendSeries");
        assertThat(itemRefOf(schemas, "TrendSeries", "points")).isEqualTo("TrendPoint");

        // 名前だけ分かれていても中身が入れ替わっていれば同じ事故になる
        assertThat(propertiesOf(schemas, "FindingItem")).containsKeys("severity", "sourceUrl");
        assertThat(propertiesOf(schemas, "RunSummary")).containsKeys("commitSha", "measuredAt");
        assertThat(propertiesOf(schemas, "TrendPoint")).containsKeys("measuredAt", "value");
    }

    private String exportedSpec() throws Exception {
        if (!Files.exists(OUTPUT)) {
            OpenAPI仕様を書き出す();
        }
        return Files.readString(OUTPUT, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemasOf(String yaml) {
        Map<String, Object> document = new Yaml().load(yaml);
        Map<String, Object> components = (Map<String, Object>) document.get("components");
        return (Map<String, Object>) components.get("schemas");
    }

    /** 配列の要素が指しているスキーマ名。 */
    @SuppressWarnings("unchecked")
    private static String itemRefOf(Map<String, Object> schemas, String schemaName,
                                    String property) {
        Map<String, Object> array =
                (Map<String, Object>) propertiesOf(schemas, schemaName).get(property);
        assertThat(array).as("%s に %s がありません", schemaName, property).isNotNull();
        String ref = String.valueOf(((Map<String, Object>) array.get("items")).get("$ref"));
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(Map<String, Object> schemas,
                                                    String schemaName) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(schemaName);
        assertThat(schema).as("スキーマ %s が見つかりません", schemaName).isNotNull();
        return (Map<String, Object>) schema.get("properties");
    }
}
