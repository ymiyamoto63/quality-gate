package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PactVerificationAdapterTest {

    private final PactVerificationAdapter adapter = new PactVerificationAdapter(JsonMapper.builder().build());

    private static final String BROKER = """
            {
              "success": false,
              "providerApplicationVersion": "1.2.3",
              "testResults": [
                { "interactionId": "a1", "interactionDescription": "GET /runs", "success": true },
                { "interactionId": "a2", "interactionDescription": "POST /runs", "success": false,
                  "mismatches": [ { "attribute": "status", "description": "expected 201 but was 500" } ] },
                { "interactionId": "a3", "interactionDescription": "GET /runs/1", "success": true }
              ]
            }
            """;

    private static final String JVM = """
            {
              "metaData": { "formatVersion": "1.0.0" },
              "provider": { "name": "quality-gate" },
              "execution": [{
                "consumer": { "name": "dashboard" },
                "interactions": [
                  { "interaction": { "description": "a request for runs" }, "verification": { "result": "OK" } },
                  { "interaction": { "description": "a request for trends" },
                    "verification": { "result": "failed", "message": "Body mismatch" } }
                ]
              }]
            }
            """;

    @Test
    void ブローカー形式の検証結果をインタラクションごとに数える() {
        NormalizedReport report = adapter.parse(stream(BROKER), context());

        assertThat(report.measurements().getFirst().metricId()).isEqualTo("M-08");
        assertThat(report.measurements().getFirst().value()).isEqualByComparingTo("66.6666");
        assertThat(report.measurements().getFirst().detail())
                .containsEntry("executed", 3L).containsEntry("passed", 2L).containsEntry("failed", 1L);
        RawFinding failure = report.findings().getFirst();
        assertThat(failure.title()).contains("POST /runs");
        assertThat(failure.detail()).containsEntry("message", "expected 201 but was 500");
    }

    @Test
    void pactJvmのJSONレポートを読む() {
        NormalizedReport report = adapter.parse(stream(JVM), context());

        assertThat(report.measurements().getFirst().value()).isEqualByComparingTo("50.0000");
        RawFinding failure = report.findings().getFirst();
        assertThat(failure.identity()).isEqualTo("pact|dashboard#a request for trends");
        assertThat(failure.detail()).containsEntry("message", "Body mismatch");
    }

    @Test
    void インタラクションが無ければ値を持たない() {
        NormalizedReport report = adapter.parse(stream("{\"testResults\": []}"), context());

        assertThat(report.measurements().getFirst().value()).isNull();
    }

    @Test
    void 形式が違えば理由つきで拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream("{\"success\": true}"), context()))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("testResults");
        assertThatThrownBy(() -> adapter.parse(stream("not json"), context()))
                .isInstanceOf(ArtifactFormatException.class);
    }

    private static ParseContext context() {
        return new ParseContext("backend", "head", List.of());
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
