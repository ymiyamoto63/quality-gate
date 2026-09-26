package com.qualitygate.release;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseReportCsvTest {

    @Test
    void 数式として解釈される値は文字列にする() {
        // 指標の理由やパスに由来する文字列が、表計算ソフトで数式として実行されないようにする
        assertThat(ReleaseReportCsv.escape("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ReleaseReportCsv.escape("@SUM(1)")).isEqualTo("'@SUM(1)");
        assertThat(ReleaseReportCsv.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(ReleaseReportCsv.escape("≥ 80%")).isEqualTo("≥ 80%");
    }

    @Test
    void ファイル名はタグをファイル名に使える形にする() {
        assertThat(ReleaseReportCsv.filename(report("release/2026-09", ReleaseRefResolver.RefType.TAG)))
                .isEqualTo("release_o_r_release_2026-09_aaaaaaa.csv");
        assertThat(ReleaseReportCsv.filename(report("aaaaaaa", ReleaseRefResolver.RefType.COMMIT)))
                .isEqualTo("release_o_r_aaaaaaa.csv");
    }

    private static ReleaseReportResponse report(String ref, ReleaseRefResolver.RefType type) {
        return new ReleaseReportResponse(UUID.randomUUID(), "o/r", ref, type, "a".repeat(40), "", ReleaseDecision.UNDETERMINED,
                "", null, 0, null, new ReleaseReportResponse.ReleaseCounts(0, 0, 0, 0, 0, 0), List.of(), List.of());
    }
}
