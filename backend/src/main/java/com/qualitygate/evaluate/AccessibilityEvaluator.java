package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.model.WcagStandard;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * M-10 アクセシビリティ違反（docs/spec/02-metrics-spec.md M-10）。
 *
 * <p>判定の優先順位は次のとおり。上で決まったものは下を見ない。
 * <ol>
 *   <li>読み込みに失敗したページがある、検査したページが無い、
 *       設定の {@code pages} に検査されていないものがある → ERROR</li>
 *   <li>基準内の critical + serious が合格ラインを超える → FAIL</li>
 *   <li>検査したルールが基準より狭い、基準内の moderate がある、
 *       重大な違反が前回より増えた → WARN</li>
 * </ol>
 *
 * <p><strong>基準（WCAG の版とレベル）に含まれないルールの違反は判定に使わない。</strong>
 * best-practice や AAA のルールは WCAG 2.2 AA の違反ではない。件数と一覧には残す。
 */
@Component
public class AccessibilityEvaluator implements MetricEvaluator {

    private static final String UNIT = "count";

    @Override
    public String metricId() {
        return GateThresholds.M_ACCESSIBILITY;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        GateThresholds thresholds = context.thresholds();
        WcagStandard standard = thresholds.accessibilityStandard();
        Coverage coverage = Coverage.of(context.input().measurementsOf(metricId()));
        List<IdentifiedFinding> findings = context.input().headFindingsOf(metricId());

        long critical = 0;
        long serious = 0;
        long moderate = 0;
        long minor = 0;
        long outOfStandard = 0;
        for (IdentifiedFinding finding : findings) {
            if (!standard.covers(tagsOf(finding))) {
                outOfStandard++;
                continue;
            }
            Severity severity = finding.finding().severity();
            switch (severity) {
                case CRITICAL -> critical++;
                case HIGH -> serious++;
                case MEDIUM -> moderate++;
                default -> minor++;
            }
        }
        long blocking = critical + serious;

        Map<String, Object> threshold = Map.of(
                "operator", "<=",
                "value", thresholds.maxAccessibilityViolations(),
                "standard", standard.wire());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("critical", critical);
        detail.put("serious", serious);
        detail.put("moderate", moderate);
        detail.put("minor", minor);
        detail.put("outOfStandard", outOfStandard);
        detail.put("needsReview", coverage.needsReview());
        detail.put("pages", List.copyOf(coverage.pages()));
        detail.put("failedPages", List.copyOf(coverage.failedPages()));
        detail.put("standard", standard.wire());
        detail.put("engines", List.copyOf(coverage.engines()));

        // 違反はすべて残す。基準外や軽微なものも、一覧で見られなければ直しようがない
        String error = errorOf(coverage, thresholds.accessibilityPages());
        if (error != null) {
            return List.of(MetricResult.of(metricId(), null, MeasurementStatus.ERROR, null,
                    UNIT, threshold, error, detail, findings));
        }

        Judgement judgement = judge(blocking, critical, serious, moderate, outOfStandard,
                coverage, standard, context, thresholds);
        return List.of(MetricResult.of(metricId(), null, judgement.status(),
                BigDecimal.valueOf(blocking), UNIT, threshold, judgement.reason(), detail,
                findings));
    }

    /**
     * 値を確定できない状態。いずれも「検査したつもりで検査していない」ことを
     * 違反 0 件の合格と見分けるためにある。空のページは必ず違反 0 件になる。
     */
    private static String errorOf(Coverage coverage, List<String> configuredPages) {
        if (!coverage.failedPages().isEmpty()) {
            return "読み込みに失敗したページがあります（%s）。そのページは検査できていません"
                    .formatted(String.join(", ", coverage.failedPages()));
        }
        if (coverage.pages().isEmpty()) {
            return "検査したページがありません。axe-core の結果が空です";
        }
        List<String> missing = new ArrayList<>();
        for (String configured : configuredPages) {
            if (coverage.pages().stream().noneMatch(page -> matches(configured, page))) {
                missing.add(configured);
            }
        }
        if (!missing.isEmpty()) {
            return "設定で検査対象としたページのうち %d 件が検査されていません（%s）"
                    .formatted(missing.size(), String.join(", ", missing))
                    + "。ログイン切れなどで別のページへ移っていないか確認してください";
        }
        return null;
    }

    private Judgement judge(long blocking, long critical, long serious, long moderate,
                            long outOfStandard, Coverage coverage, WcagStandard standard,
                            EvaluationContext context, GateThresholds thresholds) {
        int pageCount = coverage.pages().size();
        if (blocking > thresholds.maxAccessibilityViolations()) {
            return new Judgement(MeasurementStatus.FAIL,
                    "重大な違反が %d 件あります（critical %d / serious %d、%d ページを検査）"
                            .formatted(blocking, critical, serious, pageCount));
        }

        String narrowed = narrowedRules(coverage, standard);
        if (narrowed != null) {
            return new Judgement(MeasurementStatus.WARN,
                    "検査したルールが基準 %s より狭いため、違反を見逃している可能性があります（%s）"
                            .formatted(standard.wire(), narrowed));
        }
        if (moderate > 0) {
            return new Judgement(MeasurementStatus.WARN,
                    "重大な違反はありませんが、moderate の違反が %d 件あります（%d ページを検査）"
                            .formatted(moderate, pageCount));
        }
        BigDecimal previous = context.previousValue(metricId(), null).orElse(null);
        if (previous != null && BigDecimal.valueOf(blocking).compareTo(previous) > 0) {
            return new Judgement(MeasurementStatus.WARN,
                    "重大な違反が前回より増えています（%s 件 → %d 件）"
                            .formatted(previous.toPlainString(), blocking));
        }

        String outside = outOfStandard == 0 ? ""
                : "。基準外（best-practice など）の違反 %d 件は判定対象外".formatted(outOfStandard);
        return new Judgement(MeasurementStatus.PASS,
                "重大な違反はありません（%d ページを %s で検査%s）"
                        .formatted(pageCount, standard.wire(), outside));
    }

    /**
     * 基準のタグを絞り込みで外している、ルールを無効化している、ルールを個別に
     * 指定している場合に、その内容を返す。いずれでもなければ null。
     *
     * <p>タグの絞り込みが無ければ axe は全ルールを実行するため、基準は満たしている。
     */
    private static String narrowedRules(Coverage coverage, WcagStandard standard) {
        Set<String> missingTags = new TreeSet<>();
        for (List<String> filter : coverage.tagFilters()) {
            for (String tag : standard.tags()) {
                if (!filter.contains(tag)) {
                    missingTags.add(tag);
                }
            }
        }
        List<String> reasons = new ArrayList<>();
        if (!missingTags.isEmpty()) {
            reasons.add("検査していないタグ: " + String.join(", ", missingTags));
        }
        if (!coverage.disabledRules().isEmpty()) {
            reasons.add("無効にしたルール: " + String.join(", ", coverage.disabledRules()));
        }
        if (coverage.ruleFiltered()) {
            reasons.add("ルールを個別に指定しています");
        }
        return reasons.isEmpty() ? null : String.join(" / ", reasons);
    }

    /**
     * 設定のページ（{@code /runs/:id}）と検査したページを照合する。
     * {@code :} で始まる区切りは任意の 1 区切りに一致する。
     */
    static boolean matches(String configured, String page) {
        String[] expected = trimSlash(configured).split("/", -1);
        String[] actual = trimSlash(page).split("/", -1);
        if (expected.length != actual.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            boolean parameter = expected[i].startsWith(":") && !actual[i].isEmpty();
            if (!parameter && !expected[i].equals(actual[i])) {
                return false;
            }
        }
        return true;
    }

    private static String trimSlash(String path) {
        String trimmed = path.strip();
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static List<String> tagsOf(IdentifiedFinding finding) {
        Object tags = finding.finding().detail().get("tags");
        if (!(tags instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /** 成果物（複数可）から集めた、何をどう検査したか。 */
    private record Coverage(Set<String> pages, Set<String> failedPages,
                            Set<List<String>> tagFilters, Set<String> disabledRules,
                            boolean ruleFiltered, long needsReview, Set<String> engines) {

        static Coverage of(List<RawMeasurement> measurements) {
            Set<String> pages = new TreeSet<>();
            Set<String> failedPages = new TreeSet<>();
            Set<List<String>> tagFilters = new java.util.LinkedHashSet<>();
            Set<String> disabledRules = new TreeSet<>();
            Set<String> engines = new TreeSet<>();
            boolean ruleFiltered = false;
            long needsReview = 0;
            for (RawMeasurement measurement : measurements) {
                Map<String, Object> detail = measurement.detail();
                pages.addAll(strings(detail.get("pages")));
                failedPages.addAll(strings(detail.get("failedPages")));
                disabledRules.addAll(strings(detail.get("disabledRules")));
                engines.addAll(strings(detail.get("engines")));
                if (detail.get("tagFilters") instanceof List<?> filters) {
                    filters.forEach(filter -> tagFilters.add(strings(filter)));
                }
                ruleFiltered |= Boolean.TRUE.equals(detail.get("ruleFiltered"));
                if (detail.get("needsReview") instanceof Number number) {
                    needsReview += number.longValue();
                }
            }
            return new Coverage(pages, failedPages, tagFilters, disabledRules, ruleFiltered,
                    needsReview, engines);
        }

        private static List<String> strings(Object value) {
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().map(String::valueOf).toList();
        }
    }

    private record Judgement(MeasurementStatus status, String reason) {
    }
}
