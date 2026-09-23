package com.qualitygate.adapter;

import com.qualitygate.domain.model.Severity;
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

class OsvJsonAdapterTest {

    private final OsvJsonAdapter adapter = new OsvJsonAdapter(JsonMapper.builder().build());

    private static final String OSV = """
            {
              "results": [{
                "source": { "path": "frontend/package-lock.json", "type": "lockfile" },
                "packages": [{
                  "package": { "name": "lodash", "version": "4.17.20", "ecosystem": "npm" },
                  "vulnerabilities": [
                    { "id": "GHSA-35jh-r3h4-6jhm", "aliases": ["CVE-2021-23337"],
                      "summary": "Command Injection in lodash",
                      "database_specific": { "severity": "HIGH" } },
                    { "id": "GHSA-29mw-wpgm-hmr9", "aliases": ["CVE-2020-28500"],
                      "summary": "ReDoS in lodash",
                      "database_specific": { "severity": "MODERATE" } }
                  ],
                  "groups": [
                    { "ids": ["GHSA-35jh-r3h4-6jhm"], "aliases": ["CVE-2021-23337", "GHSA-35jh-r3h4-6jhm"],
                      "max_severity": "7.2" },
                    { "ids": ["GHSA-29mw-wpgm-hmr9"], "aliases": ["CVE-2020-28500"] }
                  ]
                }]
              }]
            }
            """;

    @Test
    void グループごとに1件としCVEをルールIDにする() {
        NormalizedReport report = adapter.parse(stream(OSV), context(List.of()));

        assertThat(report.findings()).hasSize(2);
        RawFinding first = report.findings().getFirst();
        assertThat(first.metricId()).isEqualTo("M-06");
        assertThat(first.ruleId()).isEqualTo("CVE-2021-23337");
        assertThat(first.identity()).isEqualTo("lodash|CVE-2021-23337");
        assertThat(first.filePath()).isEqualTo("frontend/package-lock.json");
        assertThat(first.title()).isEqualTo("Command Injection in lodash");
        assertThat(first.detail()).containsEntry("package", "lodash").containsEntry("installedVersion", "4.17.20");
    }

    @Test
    void 深刻度はCVSSを優先し無ければ表記から写す() {
        NormalizedReport report = adapter.parse(stream(OSV), context(List.of()));

        assertThat(report.findings()).extracting(RawFinding::severity)
                .containsExactly(Severity.HIGH, Severity.MEDIUM);
    }

    @Test
    void groupsが無い版では脆弱性ごとに数える() {
        String old = """
                { "results": [{ "source": { "path": "go.mod" }, "packages": [{
                  "package": { "name": "golang.org/x/net", "version": "0.1.0" },
                  "vulnerabilities": [
                    { "id": "GO-2023-1571", "aliases": ["CVE-2022-41723"],
                      "database_specific": { "severity": "CRITICAL" } },
                    { "id": "GO-2023-1988" }
                  ] }] }] }
                """;
        NormalizedReport report = adapter.parse(stream(old), context(List.of()));

        assertThat(report.findings()).extracting(RawFinding::ruleId)
                .containsExactly("CVE-2022-41723", "GO-2023-1988");
        assertThat(report.findings()).extracting(RawFinding::severity)
                .containsExactly(Severity.CRITICAL, Severity.MEDIUM);
    }

    @Test
    void 除外したロックファイルの脆弱性は数えない() {
        NormalizedReport report = adapter.parse(stream(OSV), context(List.of("frontend/**")));

        assertThat(report.findings()).isEmpty();
    }

    @Test
    void 形式が違えば理由つきで拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream("{\"runs\": []}"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("results 配列");
    }

    private static ParseContext context(List<String> exclusions) {
        return new ParseContext(null, "head", exclusions);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
