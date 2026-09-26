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

class SarifAdapterTest {

    private final SarifAdapter adapter = new SarifAdapter(JsonMapper.builder().build());

    private static final String TRIVY = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": { "driver": { "name": "Trivy", "rules": [
                  { "id": "CVE-2026-1234",
                    "helpUri": "https://example.com/CVE-2026-1234",
                    "properties": { "security-severity": "8.1" } },
                  { "id": "CVE-2026-9999",
                    "properties": { "security-severity": "9.4" } }
                ]}},
                "results": [
                  { "ruleId": "CVE-2026-1234",
                    "level": "error",
                    "message": { "text": "example-lib の任意コード実行" },
                    "properties": { "package": "com.example:example-lib" },
                    "locations": [{ "physicalLocation": {
                      "artifactLocation": { "uri": "backend/pom.xml" },
                      "region": { "startLine": 42 } }}] },
                  { "ruleId": "CVE-2026-9999",
                    "level": "error",
                    "message": { "text": "another-lib のリモートコード実行" },
                    "properties": { "package": "com.example:another-lib" },
                    "locations": [{ "physicalLocation": {
                      "artifactLocation": { "uri": "backend/pom.xml" } }}] }
                ]
              }]
            }
            """;

    @Test
    void CVSSスコアから深刻度を正規化する() {
        NormalizedReport report = adapter.parse(stream(TRIVY), context(List.of()));

        assertThat(report.findings()).extracting(RawFinding::severity)
                .containsExactly(Severity.HIGH, Severity.CRITICAL);
    }

    @Test
    void ツール固有のseverityではなくCVSSを正とする() {
        // 2 件とも SARIF の level は error（そのままなら両方 HIGH）だが、
        // CVSS 9.4 の方は CRITICAL になる
        NormalizedReport report = adapter.parse(stream(TRIVY), context(List.of()));

        assertThat(report.findings().get(1).detail()).containsEntry("cvssScore",
                new java.math.BigDecimal("9.4"));
    }

    @Test
    void ファイルパスと行番号を取り出す() {
        NormalizedReport report = adapter.parse(stream(TRIVY), context(List.of()));

        RawFinding first = report.findings().getFirst();
        assertThat(first.filePath()).isEqualTo("backend/pom.xml");
        assertThat(first.line()).isEqualTo(42);
        assertThat(first.detail()).containsEntry("advisoryUrl", "https://example.com/CVE-2026-1234");
    }

    @Test
    void 名寄せのキーはパッケージ名と脆弱性IDで作る() {
        NormalizedReport report = adapter.parse(stream(TRIVY), context(List.of()));

        // 依存の更新で解消したことを追跡するため、バージョンは含めない
        assertThat(report.findings().getFirst().identity())
                .isEqualTo("com.example:example-lib|CVE-2026-1234");
    }

    @Test
    void シークレット混入は常に重大として扱う() {
        String gitleaks = """
                { "version": "2.1.0", "runs": [{
                  "tool": { "driver": { "name": "gitleaks" } },
                  "results": [{ "ruleId": "aws-access-key", "level": "note",
                    "message": { "text": "AWS のアクセスキーが含まれています" },
                    "locations": [{ "physicalLocation": {
                      "artifactLocation": { "uri": "src/config.ts" } }}] }]
                }]}
                """;

        NormalizedReport report = adapter.parse(stream(gitleaks), context(List.of()));

        // level が note でも、有効な認証情報の流出は常に重大
        assertThat(report.findings().getFirst().severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void CVSSが無ければSARIFのlevelから写す() {
        String semgrep = """
                { "version": "2.1.0", "runs": [{
                  "tool": { "driver": { "name": "Semgrep" } },
                  "results": [
                    { "ruleId": "r1", "level": "error", "message": { "text": "a" },
                      "locations": [{ "physicalLocation": {
                        "artifactLocation": { "uri": "a.java" } }}] },
                    { "ruleId": "r2", "level": "warning", "message": { "text": "b" },
                      "locations": [{ "physicalLocation": {
                        "artifactLocation": { "uri": "b.java" } }}] },
                    { "ruleId": "r3", "level": "note", "message": { "text": "c" },
                      "locations": [{ "physicalLocation": {
                        "artifactLocation": { "uri": "c.java" } }}] }
                  ]
                }]}
                """;

        NormalizedReport report = adapter.parse(stream(semgrep), context(List.of()));

        assertThat(report.findings()).extracting(RawFinding::severity)
                .containsExactly(Severity.HIGH, Severity.MEDIUM, Severity.LOW);
    }

    @Test
    void 複雑度ツールのSARIFは脆弱性として扱わない() {
        // ここで混ぜると M-06 の件数に複雑度違反が混入する
        String pmd = """
                { "version": "2.1.0", "runs": [{
                  "tool": { "driver": { "name": "PMD" } },
                  "results": [{ "ruleId": "CyclomaticComplexity", "level": "warning",
                    "message": { "text": "complexity of 20" },
                    "locations": [{ "physicalLocation": {
                      "artifactLocation": { "uri": "A.java" } }}] }]
                }]}
                """;

        NormalizedReport report = adapter.parse(stream(pmd), context(List.of()));

        assertThat(report.findings()).isEmpty();
    }

    @Test
    void 除外パターンに一致する違反は無視する() {
        NormalizedReport report = adapter.parse(stream(TRIVY), context(List.of("backend/pom.xml")));

        assertThat(report.findings()).isEmpty();
    }

    @Test
    void SARIFでなければ理由つきで拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream("{\"foo\":1}"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("runs 配列が見つかりません");
    }

    /** Trivy 0.74 が脆弱性・シークレット・ライセンスを走査したときの SARIF（実際の出力を縮めたもの）。 */
    private static final String TRIVY_ALL_SCANNERS = """
            { "version": "2.1.0", "runs": [{
              "tool": { "driver": { "name": "Trivy", "rules": [
                { "id": "CVE-2026-1234", "name": "LanguageSpecificPackageVulnerability",
                  "properties": { "security-severity": "8.1", "tags": ["vulnerability", "security", "HIGH"] } },
                { "id": "aws-access-key-id", "name": "Secret",
                  "properties": { "security-severity": "9.5", "tags": ["secret", "security", "CRITICAL"] } },
                { "id": "ch.qos.logback:logback-core:LGPL-2.1-only", "name": "License",
                  "properties": { "security-severity": "8.0", "tags": ["license", "security", "HIGH"] } },
                { "id": "ch.qos.logback:logback-core:EPL-2.0", "name": "License",
                  "properties": { "security-severity": "5.5", "tags": ["license", "security", "MEDIUM"] } }
              ]}},
              "results": [
                { "ruleId": "CVE-2026-1234", "level": "error", "message": { "text": "脆弱性" },
                  "properties": { "package": "com.example:lib" },
                  "locations": [{ "physicalLocation": { "artifactLocation": { "uri": "backend/pom.xml" } } }] },
                { "ruleId": "aws-access-key-id", "level": "error",
                  "message": { "text": "Artifact: config.py / Secret AWS Access Key ID / Match: KEY = ****" },
                  "locations": [{ "physicalLocation": { "artifactLocation": { "uri": "config.py" },
                    "region": { "startLine": 3 } } }] },
                { "ruleId": "ch.qos.logback:logback-core:LGPL-2.1-only", "level": "error",
                  "message": { "text": "Artifact: backend/pom.xml\\nLicense LGPL-2.1-only\\nPkgName: ch.qos.logback:logback-core\\n Classification: restricted" },
                  "locations": [{ "physicalLocation": { "artifactLocation": { "uri": "backend/pom.xml" } } }] },
                { "ruleId": "ch.qos.logback:logback-core:EPL-2.0", "level": "warning",
                  "message": { "text": "Artifact: backend/pom.xml\\nLicense EPL-2.0\\nPkgName: ch.qos.logback:logback-core\\n Classification: reciprocal" },
                  "locations": [{ "physicalLocation": { "artifactLocation": { "uri": "backend/pom.xml" } } }] }
              ]
            }]}
            """;

    @Test
    void 走査した対象を宣言すればシークレットとライセンスを別の指標に振り分ける() {
        NormalizedReport report = adapter.parse(stream(TRIVY_ALL_SCANNERS), new ParseContext(null, "head",
                List.of(), java.util.Map.of("scanners", List.of("vuln", "secret", "license"))));

        assertThat(report.metricIdsWithData()).containsExactlyInAnyOrder("M-06", "M-12", "M-13");
        assertThat(report.findings()).extracting(RawFinding::metricId)
                .containsExactly("M-06", "M-12", "M-13", "M-13");
        RawFinding secret = report.findings().get(1);
        assertThat(secret.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(secret.filePath()).isEqualTo("config.py");
        RawFinding license = report.findings().get(2);
        assertThat(license.detail())
                .containsEntry("package", "ch.qos.logback:logback-core")
                .containsEntry("license", "LGPL-2.1-only")
                .containsEntry("classification", "restricted");
        assertThat(license.severity()).isEqualTo(Severity.HIGH);
        assertThat(license.identity()).isEqualTo("ch.qos.logback:logback-core|LGPL-2.1-only");
    }

    @Test
    void 宣言していない対象の検出は捨て値も与えない() {
        // ライセンスだけを走査した SARIF で、M-06 を「0 件」として合格にしない
        NormalizedReport report = adapter.parse(stream(TRIVY_ALL_SCANNERS), new ParseContext(null, "head",
                List.of(), java.util.Map.of("scanners", List.of("license"))));

        assertThat(report.metricIdsWithData()).containsExactly("M-13");
        assertThat(report.findings()).extracting(RawFinding::metricId).containsOnly("M-13");
    }

    @Test
    void 宣言が無ければ従来どおりすべてをM06として読む() {
        // シークレットの分離を知らない送り手のシークレットを、判定から黙って消さない
        NormalizedReport report = adapter.parse(stream(TRIVY_ALL_SCANNERS), context(List.of()));

        assertThat(report.metricIdsWithData()).containsExactlyInAnyOrder("M-06", "M-07");
        assertThat(report.findings()).extracting(RawFinding::metricId).containsOnly("M-06");
    }

    @Test
    void 未知の走査対象はERRORにする() {
        assertThatThrownBy(() -> adapter.parse(stream(TRIVY_ALL_SCANNERS), new ParseContext(null, "head",
                List.of(), java.util.Map.of("scanners", List.of("vulnerabilities")))))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("vulnerabilities");
    }

    private static ParseContext context(List<String> exclusions) {
        return new ParseContext(null, "head", exclusions);
    }

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
