package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IstanbulJsonAdapterTest {

    private final IstanbulJsonAdapter adapter = new IstanbulJsonAdapter(JsonMapper.builder().build());

    private static final String COVERAGE = """
            {
              "/work/frontend/src/stores/ui.ts": {
                "path": "/work/frontend/src/stores/ui.ts",
                "b": { "0": [3, 0], "1": [1, 1, 0] }
              },
              "/work/frontend/src/main.ts": {
                "path": "/work/frontend/src/main.ts",
                "b": { "0": [0, 0] }
              }
            }
            """;

    @Test
    void 分岐の経路ごとに実行の有無を数える() {
        NormalizedReport report = adapter.parse(stream(COVERAGE), context(List.of()));

        // 実行された経路 3 / 全経路 7
        assertThat(report.measurements().getFirst().value()).isEqualByComparingTo("42.8571");
        assertThat(report.measurements().getFirst().detail())
                .containsEntry("coveredBranches", 3L)
                .containsEntry("totalBranches", 7L)
                .containsEntry("files", 2);
    }

    @Test
    void 除外したファイルの分岐は数えない() {
        NormalizedReport report = adapter.parse(stream(COVERAGE), context(List.of("**/main.ts")));

        assertThat(report.measurements().getFirst().value()).isEqualByComparingTo("60.0000");
        assertThat(report.measurements().getFirst().detail()).containsEntry("excludedFiles", 1);
    }

    @Test
    void 分岐の無いファイルだけなら値を持たない() {
        NormalizedReport report = adapter.parse(stream("{\"a.ts\": {\"path\": \"a.ts\", \"b\": {}}}"),
                context(List.of()));

        assertThat(report.measurements().getFirst().value()).isNull();
    }

    @Test
    void 形式が違えば理由つきで拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream("{}"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("ファイルがありません");
        assertThatThrownBy(() -> adapter.parse(stream("{\"a.ts\": {\"s\": {}}}"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("b がありません");
        assertThatThrownBy(() -> adapter.parse(stream("[]"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class);
    }

    private static ParseContext context(List<String> exclusions) {
        return new ParseContext("frontend", "head", exclusions);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
