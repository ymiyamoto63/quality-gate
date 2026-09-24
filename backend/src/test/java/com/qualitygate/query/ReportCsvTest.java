package com.qualitygate.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportCsvTest {

    @Test
    void 区切り文字と引用符と改行を含むセルは引用符で囲む() {
        assertThat(ReportQueryService.csv("a,b")).isEqualTo("\"a,b\"");
        assertThat(ReportQueryService.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ReportQueryService.csv("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(ReportQueryService.csv(null)).isEmpty();
    }

    @Test
    void 数式として解釈される先頭文字は無害化し数値はそのまま() {
        assertThat(ReportQueryService.csv("=HYPERLINK(\"x\")")).startsWith("\"'=");
        assertThat(ReportQueryService.csv("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(ReportQueryService.csv("-3.5")).isEqualTo("-3.5");
        assertThat(ReportQueryService.csv("+cmd")).isEqualTo("'+cmd");
    }
}
