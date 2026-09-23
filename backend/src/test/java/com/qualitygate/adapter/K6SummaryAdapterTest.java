package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class K6SummaryAdapterTest {

    private static final Map<String, Object> ENVIRONMENT = Map.of("environment",
            Map.of("name", "perf-staging", "runner", "self-hosted", "datasetProfile", "prod-like"));

    private final K6SummaryAdapter adapter = new K6SummaryAdapter(JsonMapper.builder().build());

    @Test
    void summaryExportの形を読める() {
        NormalizedReport report = parse("""
                {
                  "metrics": {
                    "http_req_duration": {"avg": 210.1, "p(90)": 380.2, "p(95)": 412.5},
                    "http_req_duration{scenario:dashboard}": {"p(95)": 300.0},
                    "http_req_duration{scenario:run-detail}": {"p(95)": 455.5},
                    "http_reqs": {"count": 15000, "rate": 50.0},
                    "http_req_failed": {"passes": 15, "fails": 14985, "value": 0.001}
                  }
                }
                """, ENVIRONMENT);

        RawMeasurement p95 = measurement(report, "M-03");
        assertThat(p95.value()).isEqualByComparingTo("412.5");
        assertThat(p95.unit()).isEqualTo("ms");
        assertThat(p95.variant()).isEqualTo("perf-staging");
        @SuppressWarnings("unchecked")
        Map<String, Object> scenarios = (Map<String, Object>) p95.detail().get("scenarios");
        assertThat(scenarios)
                .containsEntry("dashboard", new BigDecimal("300.0"))
                .containsEntry("run-detail", new BigDecimal("455.5"));

        // passes は「失敗した（真）」の件数。名前に引きずられて取り違えない
        assertThat(measurement(report, "M-05").value()).isEqualByComparingTo("0.1");
        assertThat(measurement(report, "M-04").value()).isEqualByComparingTo("49.95");
    }

    @Test
    void handleSummaryの形も読める() {
        NormalizedReport report = parse("""
                {
                  "metrics": {
                    "http_req_duration": {"type": "trend", "values": {"p(95)": 250}},
                    "http_reqs": {"type": "counter", "values": {"count": 1000, "rate": 20}},
                    "http_req_failed": {"type": "rate", "values": {"rate": 0, "passes": 0, "fails": 1000}}
                  }
                }
                """, ENVIRONMENT);

        assertThat(measurement(report, "M-03").value()).isEqualByComparingTo("250");
        assertThat(measurement(report, "M-05").value()).isEqualByComparingTo("0");
        assertThat(measurement(report, "M-04").value()).isEqualByComparingTo("20");
    }

    @Test
    void 計測区間のタグ付き指標があればウォームアップを除いた値を使う() {
        NormalizedReport report = parse("""
                {
                  "metrics": {
                    "http_req_duration": {"p(95)": 900},
                    "http_req_duration{phase:measure}": {"p(95)": 320},
                    "http_reqs": {"count": 18000, "rate": 50},
                    "http_reqs{phase:measure}": {"count": 15000, "rate": 50},
                    "http_req_failed": {"passes": 30, "fails": 17970},
                    "http_req_failed{phase:measure}": {"passes": 0, "fails": 15000}
                  }
                }
                """, ENVIRONMENT);

        assertThat(measurement(report, "M-03").value()).isEqualByComparingTo("320");
        assertThat(measurement(report, "M-05").value()).isEqualByComparingTo("0");
        assertThat(measurement(report, "M-03").detail()).containsEntry("requests", 15000L);
    }

    @Test
    void 環境名が無ければ読めない() {
        assertThatThrownBy(() -> parse("{\"metrics\":{}}", Map.of()))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("environment.name");
    }

    @Test
    void リクエスト0件は読めない() {
        assertThatThrownBy(() -> parse("""
                {"metrics": {"http_req_duration": {"p(95)": 1}, "http_reqs": {"count": 0, "rate": 0},
                 "http_req_failed": {"passes": 0, "fails": 0}}}
                """, ENVIRONMENT))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("1 件も記録されていません");
    }

    @Test
    void 異常終了の申告があれば読まない() {
        Map<String, Object> metadata = Map.of("environment", Map.of("name", "perf"),
                "aborted", true);
        assertThatThrownBy(() -> parse("{\"metrics\":{}}", metadata))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("異常終了");
    }

    @Test
    void p95が無ければ読めない() {
        assertThatThrownBy(() -> parse("""
                {"metrics": {"http_req_duration": {"avg": 1}}}
                """, ENVIRONMENT))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("p(95)");
    }

    private NormalizedReport parse(String json, Map<String, Object> metadata) {
        return adapter.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                new ParseContext(null, null, List.of(), metadata));
    }

    private static RawMeasurement measurement(NormalizedReport report, String metricId) {
        return report.measurements().stream()
                .filter(m -> m.metricId().equals(metricId)).findFirst().orElseThrow();
    }
}
