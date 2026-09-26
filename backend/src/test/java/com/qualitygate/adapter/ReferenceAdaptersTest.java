package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 参考値の指標（M-15 / M-16 / M-17）のアダプタ。 */
class ReferenceAdaptersTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ParseContext frontend = new ParseContext("frontend", null, List.of("**/vendor-*.js"));

    @Test
    void jscpdのレポートから重複率を行数で計算し直す() {
        // jscpd 5.3 が quality-gate の backend に出したレポートの statistics.total
        NormalizedReport report = new JscpdJsonAdapter(mapper).parse(stream("""
                { "statistics": { "total": { "lines": 19873, "duplicatedLines": 960, "clones": 108,
                  "sources": 228, "percentage": 4.830674784884013 } }, "duplicates": [] }
                """), new ParseContext("backend", null, List.of()));

        RawMeasurement measurement = report.measurements().getFirst();
        assertThat(measurement.metricId()).isEqualTo("M-15");
        assertThat(measurement.componentName()).isEqualTo("backend");
        assertThat(measurement.value()).isEqualByComparingTo("4.83");
        assertThat(measurement.detail()).containsEntry("lines", 19873L).containsEntry("clones", 108L);
    }

    @Test
    void jscpdのレポートでなければERROR() {
        assertThatThrownBy(() -> new JscpdJsonAdapter(mapper).parse(stream("{\"duplicates\": []}"), frontend))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("statistics.total");
    }

    @Test
    void Lighthouseの結果からスコアと主要な指標を読む() {
        NormalizedReport report = new LighthouseJsonAdapter(mapper).parse(
                getClass().getResourceAsStream("/lighthouse/login-desktop.json"), frontend);

        RawMeasurement measurement = report.measurements().getFirst();
        assertThat(measurement.metricId()).isEqualTo("M-16");
        assertThat(measurement.value()).isEqualByComparingTo("100");
        assertThat(measurement.unit()).isEqualTo("score");
        assertThat(measurement.detail())
                .containsEntry("page", "/login")
                .containsEntry("formFactor", "desktop")
                .containsEntry("lighthouseVersion", "13.5.0");
        assertThat((java.math.BigDecimal) measurement.detail().get("bestPractices")).isEqualByComparingTo("96");
        assertThat((java.math.BigDecimal) measurement.detail().get("seo")).isEqualByComparingTo("82");
        assertThat((java.math.BigDecimal) measurement.detail().get("lcpMs")).isEqualByComparingTo("465");
    }

    @Test
    void Lighthouseの計測が失敗していればERROR() {
        assertThatThrownBy(() -> new LighthouseJsonAdapter(mapper).parse(stream("""
                { "requestedUrl": "http://127.0.0.1:4173/",
                  "runtimeError": { "code": "NO_FCP", "message": "The page did not paint any content." },
                  "categories": { "performance": { "score": null } } }
                """), frontend))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("NO_FCP");
    }

    @Test
    void 画面はホストを除いたパスで同定する() {
        assertThat(LighthouseJsonAdapter.pageOf("http://127.0.0.1:4173/runs/1?x=1")).isEqualTo("/runs/1");
        assertThat(LighthouseJsonAdapter.pageOf("http://127.0.0.1:4173")).isEqualTo("/");
    }

    @Test
    void バンドルサイズはJSとCSSのgzip後の合計でそれ以外は内訳にだけ残す() {
        NormalizedReport report = new BundleSizeJsonAdapter(mapper).parse(stream("""
                { "files": [
                  { "path": "assets/index-abc.js", "bytes": 409600, "gzipBytes": 102400 },
                  { "path": "assets/index-abc.css", "bytes": 51200, "gzipBytes": 10240 },
                  { "path": "assets/vendor-x.js", "bytes": 999999, "gzipBytes": 99999 },
                  { "path": "assets/primeicons.woff2", "bytes": 35000, "gzipBytes": 35000 }
                ] }
                """), frontend);

        RawMeasurement measurement = report.measurements().getFirst();
        assertThat(measurement.metricId()).isEqualTo("M-17");
        // (102400 + 10240) / 1024 = 110 KB。除外に一致した vendor-x.js は数えない
        assertThat(measurement.value()).isEqualByComparingTo("110.0");
        assertThat(measurement.unit()).isEqualTo("KB");
        assertThat(measurement.detail())
                .containsEntry("jsGzipBytes", 102400L)
                .containsEntry("cssGzipBytes", 10240L)
                .containsEntry("otherBytes", 35000L);
        assertThat((List<?>) measurement.detail().get("largest")).first()
                .isEqualTo(Map.of("path", "assets/index-abc.js", "gzipBytes", 102400L));
    }

    @Test
    void バンドルサイズの形でなければERROR() {
        assertThatThrownBy(() -> new BundleSizeJsonAdapter(mapper).parse(
                stream("{\"files\": [{\"path\": \"a.js\"}]}"), frontend))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("gzipBytes");
    }

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
