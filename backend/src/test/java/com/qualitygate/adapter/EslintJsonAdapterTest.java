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

class EslintJsonAdapterTest {

    private final EslintJsonAdapter adapter = new EslintJsonAdapter(JsonMapper.builder().build());

    private static final String ESLINT = """
            [
              { "filePath": "/work/frontend/src/stores/runs.ts",
                "messages": [
                  { "ruleId": "complexity", "line": 12,
                    "message": "Function 'loadRuns' has a complexity of 17. Maximum allowed is 0." },
                  { "ruleId": "complexity", "line": 40,
                    "message": "Arrow function has a complexity of 3. Maximum allowed is 0." },
                  { "ruleId": "complexity", "line": 55,
                    "message": "Arrow function has a complexity of 1. Maximum allowed is 0." },
                  { "ruleId": "no-unused-vars", "line": 3, "message": "'x' is defined but never used." }
                ] },
              { "filePath": "/work/frontend/src/main.ts",
                "messages": [
                  { "ruleId": "complexity", "line": 1,
                    "message": "Method 'mount' has a complexity of 2. Maximum allowed is 0." }
                ] }
            ]
            """;

    @Test
    void complexityルールの報告から全関数のCC値を読む() {
        NormalizedReport report = adapter.parse(stream(ESLINT), context(List.of()));

        assertThat(report.findings()).hasSize(4);
        RawFinding first = report.findings().getFirst();
        assertThat(first.metricId()).isEqualTo("M-07");
        assertThat(first.detail()).containsEntry("complexity", 17).containsEntry("member", "loadRuns");
        assertThat(first.filePath()).isEqualTo("frontend/src/stores/runs.ts");
        assertThat(first.line()).isEqualTo(12);
        assertThat(first.identity()).isEqualTo("src/stores/runs.ts#loadRuns");
    }

    @Test
    void 名前の無い関数はファイル内の出現順で区別する() {
        NormalizedReport report = adapter.parse(stream(ESLINT), context(List.of()));

        assertThat(report.findings()).extracting(RawFinding::identity)
                .contains("src/stores/runs.ts#Arrow function#1", "src/stores/runs.ts#Arrow function#2");
    }

    @Test
    void 除外したファイルは読まない() {
        NormalizedReport report = adapter.parse(stream(ESLINT), context(List.of("**/main.ts")));

        assertThat(report.findings()).extracting(RawFinding::identity)
                .noneMatch(identity -> identity.startsWith("src/main.ts"));
    }

    @Test
    void 形式が違えば理由つきで拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream("{}"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("配列ではありません");
        assertThatThrownBy(() -> adapter.parse(stream("[{\"x\": 1}]"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("filePath");
        String broken = "[{\"filePath\": \"a.ts\", \"messages\": [{\"ruleId\": \"complexity\", \"message\": \"?\"}]}]";
        assertThatThrownBy(() -> adapter.parse(stream(broken), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("CC 値");
    }

    private static ParseContext context(List<String> exclusions) {
        return new ParseContext("frontend", "head", exclusions);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
