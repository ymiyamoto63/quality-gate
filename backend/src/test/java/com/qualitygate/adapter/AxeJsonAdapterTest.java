package com.qualitygate.adapter;

import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AxeJsonAdapterTest {

    private final AxeJsonAdapter adapter = new AxeJsonAdapter(JsonMapper.builder().build());

    private static final ParseContext CONTEXT = new ParseContext("frontend", "head", List.of());

    @Test
    void 実際のaxe_coreの出力を読める() {
        // @axe-core/playwright 4.13 が 2 ページを検査した結果（passes / inapplicable は省いた）。
        // 1 ページ目は WCAG のタグに絞り、2 ページ目は全ルールで検査している
        NormalizedReport report = adapter.parse(resource("/axe/axe-results.json"), CONTEXT);

        RawMeasurement measurement = report.measurements().getFirst();
        assertThat(measurement.metricId()).isEqualTo("M-09");
        assertThat(measurement.componentName()).isEqualTo("frontend");
        assertThat(measurement.value()).isNull();
        assertThat(measurement.detail())
                .containsEntry("pages", List.of("/orders/:id", "/orders/new"))
                .containsEntry("failedPages", List.of())
                .containsEntry("tagFilters",
                        List.of(List.of("wcag21a", "wcag21aa", "wcag22aa", "wcag2a", "wcag2aa")))
                .containsEntry("ruleFiltered", false)
                .containsEntry("engines", List.of("axe-core 4.13.0"));

        // 1 ページ目 3 件 + 2 ページ目 3 件 + best-practice（heading-order 1 / landmark 1 / region 5）
        assertThat(report.findings()).hasSize(13);
        RawFinding contrast = report.findings().getFirst();
        assertThat(contrast.ruleId()).isEqualTo("color-contrast");
        assertThat(contrast.severity()).isEqualTo(Severity.HIGH);
        assertThat(contrast.title()).isEqualTo("Elements must meet minimum color contrast ratio thresholds");
        assertThat(contrast.filePath()).isNull();
        assertThat(contrast.identity()).isEqualTo("/orders/:id|color-contrast|p");
        assertThat(contrast.detail())
                .containsEntry("page", "/orders/:id")
                .containsEntry("selector", "p")
                .containsEntry("impact", "serious")
                .containsKey("helpUrl")
                .containsKey("html")
                .containsKey("failureSummary");
        assertThat(report.findings()).filteredOn(f -> f.ruleId().equals("image-alt"))
                .extracting(RawFinding::severity).containsOnly(Severity.CRITICAL);
        assertThat(report.findings()).filteredOn(f -> f.ruleId().equals("region"))
                .extracting(RawFinding::severity).containsOnly(Severity.MEDIUM);
    }

    @Test
    void 違反はページとルールと要素の組ごとに1件とする() {
        String json = result("https://app.example.test/orders", """
                [{ "id": "label", "impact": "critical", "help": "Form elements must have labels",
                   "tags": ["wcag2a"],
                   "nodes": [ { "target": ["#q"] }, { "target": ["#name"] } ] }]
                """);

        List<RawFinding> findings = adapter.parse(stream(json), CONTEXT).findings();

        assertThat(findings).extracting(RawFinding::identity)
                .containsExactly("/orders|label|#q", "/orders|label|#name");
    }

    @Test
    void 要素ごとのimpactがあればそれを使う() {
        String json = result("https://app.example.test/", """
                [{ "id": "color-contrast", "impact": "serious", "tags": ["wcag2aa"],
                   "nodes": [ { "target": ["p"], "impact": "moderate" } ] }]
                """);

        RawFinding finding = adapter.parse(stream(json), CONTEXT).findings().getFirst();

        assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(finding.detail()).containsEntry("impact", "moderate");
    }

    @Test
    void impactの無い違反は重大側に倒す() {
        String json = result("https://app.example.test/", """
                [{ "id": "custom-rule", "impact": null, "tags": ["wcag2a"],
                   "nodes": [ { "target": ["div"], "impact": null } ] }]
                """);

        RawFinding finding = adapter.parse(stream(json), CONTEXT).findings().getFirst();

        assertThat(finding.severity()).isEqualTo(Severity.HIGH);
        assertThat(finding.detail()).containsEntry("impact", "unknown");
        assertThat(finding.title()).isEqualTo("custom-rule");
    }

    @Test
    void IDらしい区切りを置き換え同じ画面を同じページとみなす() {
        assertThat(AxeJsonAdapter.pagePathOf(
                "http://localhost:5173/runs/0190f5a2-7c1e-7a3b-9e4d-2f6a8b1c3d5e/findings?state=NEW"))
                .contains("/runs/:id/findings");
        assertThat(AxeJsonAdapter.pagePathOf("https://shop.example.test/orders/1234/"))
                .contains("/orders/:id");
        assertThat(AxeJsonAdapter.pagePathOf("https://shop.example.test/users/65f1c2a9e4b0a1b2c3d4e5f6"))
                .contains("/users/:id");
        assertThat(AxeJsonAdapter.pagePathOf("https://shop.example.test/orders/new"))
                .contains("/orders/new");
        assertThat(AxeJsonAdapter.pagePathOf("https://shop.example.test")).contains("/");
    }

    @Test
    void ハッシュ方式のルーターではフラグメントを画面のパスとして使う() {
        assertThat(AxeJsonAdapter.pagePathOf("https://app.example.test/#/orders/42?tab=open"))
                .contains("/orders/:id");
        // ページ内リンクのフラグメントは画面のパスではない
        assertThat(AxeJsonAdapter.pagePathOf("https://app.example.test/help#install"))
                .contains("/help");
    }

    @Test
    void 読み込みに失敗したページは検査したページに数えず違反も捨てる() {
        String json = "[" + result("chrome-error://chromewebdata/", """
                [{ "id": "document-title", "impact": "serious", "tags": ["wcag2a"],
                   "nodes": [ { "target": ["html"] } ] }]
                """) + "," + result("https://app.example.test/login?next=%2Fruns", "[]") + "]";

        NormalizedReport report = adapter.parse(stream(json), CONTEXT);

        assertThat(report.findings()).isEmpty();
        assertThat(report.measurements().getFirst().detail())
                .containsEntry("pages", List.of("/login"))
                .containsEntry("failedPages", List.of("chrome-error://chromewebdata/"));
    }

    @Test
    void 検査したルールの絞り込みと無効化を記録する() {
        String json = """
                [{ "url": "https://app.example.test/a", "violations": [],
                   "toolOptions": { "runOnly": { "type": "tags", "value": ["wcag2aa", "wcag2a"] },
                                    "rules": { "color-contrast": { "enabled": false },
                                               "region": { "enabled": true } } } },
                 { "url": "https://app.example.test/b", "violations": [],
                   "toolOptions": { "runOnly": { "type": "rule", "values": ["image-alt"] } } },
                 { "url": "https://app.example.test/c", "violations": [],
                   "toolOptions": { "runOnly": ["wcag2a"] } }]
                """;

        RawMeasurement measurement = adapter.parse(stream(json), CONTEXT).measurements().getFirst();

        assertThat(measurement.detail())
                .containsEntry("tagFilters", List.of(List.of("wcag2a", "wcag2aa"), List.of("wcag2a")))
                .containsEntry("ruleFiltered", true)
                .containsEntry("disabledRules", List.of("color-contrast"));
    }

    @Test
    void 要確認の要素数を記録する() {
        String json = """
                { "url": "https://app.example.test/", "violations": [],
                  "incomplete": [ { "id": "color-contrast", "nodes": [ {}, {} ] } ] }
                """;

        RawMeasurement measurement = adapter.parse(stream(json), CONTEXT).measurements().getFirst();

        assertThat(measurement.detail()).containsEntry("needsReview", 2L);
    }

    @Test
    void iframeとShadow_DOMを越えるセレクタを1つの文字列にする() {
        String json = result("https://app.example.test/", """
                [{ "id": "button-name", "impact": "critical", "tags": ["wcag2a"],
                   "nodes": [ { "target": ["iframe#pay", ["my-form", "button"]] } ] }]
                """);

        RawFinding finding = adapter.parse(stream(json), CONTEXT).findings().getFirst();

        assertThat(finding.detail()).containsEntry("selector", "iframe#pay | my-form >>> button");
    }

    @Test
    void 空の配列は検査ページ0件として読む() {
        RawMeasurement measurement = adapter.parse(stream("[]"), CONTEXT).measurements().getFirst();

        assertThat(measurement.detail()).containsEntry("pages", List.of());
    }

    @Test
    void axe_coreの結果でなければ形式不正にする() {
        // Playwright のテストレポート（JSON reporter）を取り違えて送った場合
        assertThatThrownBy(() -> adapter.parse(stream("{\"config\":{},\"suites\":[]}"), CONTEXT))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("violations");
        assertThatThrownBy(() -> adapter.parse(stream("{\"violations\":[]}"), CONTEXT))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("url");
    }

    @Test
    void JSONでなければ形式不正にする() {
        assertThatThrownBy(() -> adapter.parse(stream("<html>"), CONTEXT))
                .isInstanceOf(ArtifactFormatException.class);
    }

    private static String result(String url, String violations) {
        return """
                { "url": "%s", "testEngine": { "name": "axe-core", "version": "4.13.0" },
                  "violations": %s }
                """.formatted(url, violations);
    }

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream resource(String path) {
        return AxeJsonAdapterTest.class.getResourceAsStream(path);
    }
}
