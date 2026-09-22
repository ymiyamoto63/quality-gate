package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LcovAdapterTest {

    private final LcovAdapter adapter = new LcovAdapter();

    private static final String LCOV = """
            TN:
            SF:src/stores/ui.ts
            BRF:14
            BRH:12
            end_of_record
            SF:src/api/schema.d.ts
            BRF:100
            BRH:0
            end_of_record
            """;

    @Test
    void ファイルごとのBRFとBRHを集計する() {
        NormalizedReport report = adapter.parse(stream(LCOV), context(List.of()));

        // (12 + 0) / (14 + 100) ≈ 10.53%
        assertThat(report.measurements().getFirst().value()).isEqualByComparingTo("10.5263");
        assertThat(report.measurements().getFirst().detail()).containsEntry("files", 2);
    }

    @Test
    void 除外したファイルの分岐は数えない() {
        NormalizedReport report = adapter.parse(stream(LCOV),
                context(List.of("**/schema.d.ts")));

        assertThat(report.measurements().getFirst().value()).isEqualByComparingTo("85.7143");
        assertThat(report.measurements().getFirst().detail()).containsEntry("excludedFiles", 1);
    }

    @Test
    void SFレコードが無ければ理由つきで拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream("TN:\n"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("SF: レコードがありません");
    }

    @Test
    void 数値でない値は理由つきで拒否する() {
        String broken = "SF:a.ts\nBRF:abc\nend_of_record\n";

        assertThatThrownBy(() -> adapter.parse(stream(broken), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("BRF:");
    }

    private static ParseContext context(List<String> exclusions) {
        return new ParseContext("frontend", "head", exclusions);
    }

    private static InputStream stream(String lcov) {
        return new ByteArrayInputStream(lcov.getBytes(StandardCharsets.UTF_8));
    }
}
