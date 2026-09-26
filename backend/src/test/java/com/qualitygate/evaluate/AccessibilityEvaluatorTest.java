package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qualitygate.evaluate.EvaluatorTestSupport.input;
import static com.qualitygate.evaluate.EvaluatorTestSupport.run;
import static com.qualitygate.evaluate.EvaluatorTestSupport.thresholdsWith;
import static org.assertj.core.api.Assertions.assertThat;

class AccessibilityEvaluatorTest {

    private static final List<String> WCAG22AA_TAGS =
            List.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa");

    private final AccessibilityEvaluator evaluator = new AccessibilityEvaluator();

    @Test
    void 重大な違反が無ければ合格() {
        MetricResult result = evaluate(Map.of(), List.of(scan(List.of("/login", "/runs/:id"))),
                List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.value()).isEqualByComparingTo("0");
        assertThat(result.reason()).contains("2 ページを wcag22aa で検査");
        assertThat(result.threshold()).containsEntry("operator", "<=").containsEntry("value", 0);
    }

    @Test
    void criticalとseriousを合わせて数え1件でもあれば不合格() {
        MetricResult result = evaluate(Map.of(), List.of(scan(List.of("/login"))), List.of(
                violation("/login", "image-alt", Severity.CRITICAL, "wcag2a"),
                violation("/login", "color-contrast", Severity.HIGH, "wcag2aa"),
                violation("/login", "color-contrast", Severity.HIGH, "wcag2aa", "#footer")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.FAIL);
        assertThat(result.value()).isEqualByComparingTo("3");
        assertThat(result.reason()).contains("critical 1 / serious 2");
        assertThat(result.findingsToPersist()).hasSize(3);
    }

    @Test
    void 設定で許容した件数までは合格() {
        MetricResult result = evaluate(Map.of("max_critical", 1),
                List.of(scan(List.of("/login"))),
                List.of(violation("/login", "image-alt", Severity.CRITICAL, "wcag2a")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
    }

    @Test
    void 基準に含まれないルールの違反は判定に使わず一覧には残す() {
        // best-practice は WCAG の違反ではない。AAA は AA の基準を超える
        MetricResult result = evaluate(Map.of(), List.of(scan(List.of("/"))), List.of(
                violation("/", "region", Severity.MEDIUM, "best-practice"),
                violation("/", "scrollable-region", Severity.HIGH, "best-practice"),
                violation("/", "color-contrast-enhanced", Severity.HIGH, "wcag2aaa")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.detail()).containsEntry("outOfStandard", 3L);
        assertThat(result.reason()).contains("基準外（best-practice など）の違反 3 件は判定対象外");
        assertThat(result.findingsToPersist()).hasSize(3);
    }

    @Test
    void 基準を下げればその基準に含まれないルールは判定に使わない() {
        // WCAG 2.2 で加わった target-size は 2.1 AA の違反ではない
        MetricResult result = evaluate(Map.of("standard", "wcag21aa"),
                List.of(scan(List.of("/"))),
                List.of(violation("/", "target-size", Severity.HIGH, "wcag22aa")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.detail()).containsEntry("standard", "wcag21aa");
    }

    @Test
    void moderateがあれば警告() {
        MetricResult result = evaluate(Map.of(), List.of(scan(List.of("/"))),
                List.of(violation("/", "list", Severity.MEDIUM, "wcag2a")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("moderate の違反が 1 件");
    }

    @Test
    void minorだけなら合格() {
        MetricResult result = evaluate(Map.of(), List.of(scan(List.of("/"))),
                List.of(violation("/", "some-minor", Severity.LOW, "wcag2a")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.detail()).containsEntry("minor", 1L);
    }

    @Test
    void 重大な違反が前回より増えれば警告() {
        MetricResult result = evaluate(Map.of("max_critical", 5),
                Map.of(EvaluationContext.key("M-09", null), BigDecimal.ONE),
                List.of(scan(List.of("/"))), List.of(
                        violation("/", "image-alt", Severity.CRITICAL, "wcag2a"),
                        violation("/", "label", Severity.CRITICAL, "wcag2a")));

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("1 件 → 2 件");
    }

    @Test
    void 読み込みに失敗したページがあれば計測エラーにし値を出さない() {
        Map<String, Object> detail = scanDetail(List.of("/login"));
        detail.put("failedPages", List.of("chrome-error://chromewebdata/"));

        MetricResult result = evaluate(Map.of(), List.of(measurement(detail)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(result.value()).isNull();
        assertThat(result.reason()).contains("chrome-error://chromewebdata/");
    }

    @Test
    void 検査したページが無ければ計測エラー() {
        // 空の結果は必ず「違反 0 件」になる。合格と見分けがつかない
        MetricResult result = evaluate(Map.of(), List.of(scan(List.of())), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(result.reason()).contains("検査したページがありません");
    }

    @Test
    void 設定したページが検査されていなければ計測エラー() {
        // ログイン切れで /login に飛ばされた場合、/runs/:id は検査されない
        MetricResult result = evaluate(Map.of("pages", List.of("/login", "/runs/:id", "/")),
                List.of(scan(List.of("/login"))), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(result.reason()).contains("2 件が検査されていません").contains("/runs/:id, /");
    }

    @Test
    void 複数の成果物で検査したページを合わせて照合する() {
        MetricResult result = evaluate(Map.of("pages", List.of("/login", "/runs/:id/findings")),
                List.of(scan(List.of("/login")), scan(List.of("/runs/:id/findings"))),
                List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
        assertThat(result.detail()).containsEntry("pages", List.of("/login", "/runs/:id/findings"));
    }

    @Test
    void 基準のタグを外して検査していれば警告() {
        Map<String, Object> detail = scanDetail(List.of("/"));
        detail.put("tagFilters", List.of(List.of("wcag2a", "wcag2aa")));

        MetricResult result = evaluate(Map.of(), List.of(measurement(detail)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("wcag21a, wcag21aa, wcag22aa");
    }

    @Test
    void ルールを無効化していれば警告() {
        Map<String, Object> detail = scanDetail(List.of("/"));
        detail.put("disabledRules", List.of("color-contrast"));
        detail.put("ruleFiltered", true);

        MetricResult result = evaluate(Map.of(), List.of(measurement(detail)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.WARN);
        assertThat(result.reason()).contains("無効にしたルール: color-contrast")
                .contains("ルールを個別に指定しています");
    }

    @Test
    void 基準のタグをすべて含む絞り込みなら警告しない() {
        Map<String, Object> detail = scanDetail(List.of("/"));
        detail.put("tagFilters", List.of(WCAG22AA_TAGS));

        MetricResult result = evaluate(Map.of(), List.of(measurement(detail)), List.of());

        assertThat(result.status()).isEqualTo(MeasurementStatus.PASS);
    }

    @Test
    void 設定のページはパラメータ部分を任意の区切りに一致させる() {
        assertThat(AccessibilityEvaluator.matches("/runs/:id", "/runs/:id")).isTrue();
        assertThat(AccessibilityEvaluator.matches("/repositories/:repositoryId/trends",
                "/repositories/:id/trends")).isTrue();
        assertThat(AccessibilityEvaluator.matches("/orders/:id", "/orders/new")).isTrue();
        assertThat(AccessibilityEvaluator.matches("/runs/:id", "/runs/:id/findings")).isFalse();
        assertThat(AccessibilityEvaluator.matches("/", "/")).isTrue();
        assertThat(AccessibilityEvaluator.matches("/", "/login")).isFalse();
        assertThat(AccessibilityEvaluator.matches("/login/", "/login")).isTrue();
    }

    private MetricResult evaluate(Map<String, Object> config, List<RawMeasurement> scans,
                                  List<IdentifiedFinding> findings) {
        return evaluate(config, Map.of(), scans, findings);
    }

    private MetricResult evaluate(Map<String, Object> config, Map<String, BigDecimal> previous,
                                  List<RawMeasurement> scans, List<IdentifiedFinding> findings) {
        NormalizedInput input = input(scans, findings, List.of(), Set.of("M-09"));
        EvaluationContext context = new EvaluationContext(run(),
                thresholdsWith("accessibility", config), input, previous, !previous.isEmpty());
        List<MetricResult> results = evaluator.evaluate(context);
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    private static RawMeasurement scan(List<String> pages) {
        return measurement(scanDetail(pages));
    }

    private static Map<String, Object> scanDetail(List<String> pages) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("pages", pages);
        detail.put("failedPages", List.of());
        detail.put("tagFilters", List.of());
        detail.put("ruleFiltered", false);
        detail.put("disabledRules", List.of());
        detail.put("needsReview", 0L);
        detail.put("engines", List.of("axe-core 4.13.0"));
        return detail;
    }

    private static RawMeasurement measurement(Map<String, Object> detail) {
        return RawMeasurement.of("M-09", "frontend", null, "count", detail);
    }

    private static IdentifiedFinding violation(String page, String rule, Severity severity,
                                               String tag) {
        return violation(page, rule, severity, tag, "main");
    }

    private static IdentifiedFinding violation(String page, String rule, Severity severity,
                                               String tag, String selector) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("page", page);
        detail.put("selector", selector);
        detail.put("tags", List.of("cat.test", tag));
        String identity = page + "|" + rule + "|" + selector;
        RawFinding finding = new RawFinding("M-09", rule, severity, rule, null, null,
                "frontend", identity, detail);
        return new IdentifiedFinding("fp-" + identity, finding);
    }
}
