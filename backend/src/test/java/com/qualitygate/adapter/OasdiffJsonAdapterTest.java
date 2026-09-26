package com.qualitygate.adapter;

import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OasdiffJsonAdapterTest {

    private final OasdiffJsonAdapter adapter = new OasdiffJsonAdapter(JsonMapper.builder().build());

    @Test
    void oasdiffのbreakingの出力を読める() {
        NormalizedReport report = parse("""
                [
                  {
                    "id": "api-path-removed-without-deprecation",
                    "text": "api path removed without deprecation",
                    "level": 3,
                    "operation": "GET",
                    "operationId": "listRuns",
                    "path": "/api/v1/runs",
                    "source": "api/openapi.yml",
                    "section": "paths"
                  },
                  {
                    "id": "response-property-enum-value-added",
                    "text": "added the new enum value 'NOT_APPLICABLE' to the response property 'status'",
                    "level": 2,
                    "operation": "GET",
                    "path": "/api/v1/runs/{runId}",
                    "section": "paths"
                  }
                ]
                """, Map.of());

        RawMeasurement measurement = report.measurements().getFirst();
        assertThat(measurement.metricId()).isEqualTo("M-08");
        assertThat(measurement.value()).isEqualByComparingTo("1");
        assertThat(measurement.detail())
                .containsEntry("breaking", 1L)
                .containsEntry("warnings", 1L)
                .containsEntry("baseSpecMissing", false);

        RawFinding removed = report.findings().getFirst();
        assertThat(removed.ruleId()).isEqualTo("api-path-removed-without-deprecation");
        assertThat(removed.severity()).isEqualTo(Severity.HIGH);
        assertThat(removed.title()).isEqualTo("GET /api/v1/runs: api path removed without deprecation");
        assertThat(removed.filePath()).isNull();
        assertThat(removed.detail())
                .containsEntry("operation", "GET")
                .containsEntry("apiPath", "/api/v1/runs")
                .containsEntry("level", "error")
                .containsEntry("source", "api/openapi.yml");
        assertThat(report.findings().get(1).severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void 同じ操作の同じ種類の変更は説明文で区別する() {
        NormalizedReport report = parse("""
                [
                  {"id": "response-property-removed", "text": "removed the property 'a'",
                   "level": 3, "operation": "GET", "path": "/x"},
                  {"id": "response-property-removed", "text": "removed the property 'b'",
                   "level": 3, "operation": "GET", "path": "/x"}
                ]
                """, Map.of());

        assertThat(report.findings()).extracting(RawFinding::identity).doesNotHaveDuplicates();
    }

    @Test
    void levelは文字列でも読み未知の値は破壊的に倒す() {
        NormalizedReport report = parse("""
                [
                  {"id": "a", "level": "info"},
                  {"id": "b", "level": "warning"},
                  {"id": "c", "level": "error"},
                  {"id": "d"}
                ]
                """, Map.of());

        assertThat(report.findings()).extracting(RawFinding::severity).containsExactly(
                Severity.INFO, Severity.MEDIUM, Severity.HIGH, Severity.HIGH);
        assertThat(report.measurements().getFirst().value()).isEqualByComparingTo("2");
    }

    @Test
    void 空の配列とnullは変更0件として読む() {
        assertThat(parse("[]", Map.of()).measurements().getFirst().value())
                .isEqualByComparingTo("0");
        assertThat(parse("null", Map.of()).measurements().getFirst().value())
                .isEqualByComparingTo("0");
    }

    @Test
    void 比較元に定義が無いことをメタデータから読む() {
        RawMeasurement measurement = parse("[]", Map.of("baseSpecMissing", true))
                .measurements().getFirst();

        assertThat(measurement.detail()).containsEntry("baseSpecMissing", true);
    }

    @Test
    void 空のファイルは0件とみなさず形式不正にする() {
        // oasdiff が動かなかったことと、変更が無かったことを取り違えない
        assertThatThrownBy(() -> parse("", Map.of()))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("空です");
    }

    @Test
    void 変更の配列でなければ形式不正にする() {
        assertThatThrownBy(() -> parse("{\"paths\": {}}", Map.of()))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("変更の配列ではありません");
        assertThatThrownBy(() -> parse("[{\"text\": \"no id\"}]", Map.of()))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("id がありません");
        assertThatThrownBy(() -> parse("not json", Map.of()))
                .isInstanceOf(ArtifactFormatException.class);
    }

    private NormalizedReport parse(String json, Map<String, Object> metadata) {
        return adapter.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                new ParseContext(null, null, List.of(), metadata));
    }
}
