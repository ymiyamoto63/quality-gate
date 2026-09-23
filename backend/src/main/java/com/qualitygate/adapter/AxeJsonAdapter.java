package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * axe-core の結果 JSON から M-10（アクセシビリティ違反）を読む
 * （docs/initial/02-metrics-spec.md M-10）。
 *
 * <p>受け付けるのは {@code @axe-core/playwright} の {@code analyze()} の戻り値そのもの
 * （1 ページ分のオブジェクト）か、その配列（複数ページ分）。
 *
 * <p>違反は<strong>ページ × ルール × 要素</strong>で 1 件とする。axe は 1 つのルールに
 * 該当した要素を {@code nodes} にまとめて返すため、要素ごとに分けて数える。
 * 判定（何件を重大とみなすか、基準外のルールをどう扱うか）は評価器が決める。
 */
@Component
public class AxeJsonAdapter implements ArtifactAdapter {

    static final String METRIC_ID = "M-10";

    /** 検査できたページと読み込みに失敗したページを区別するための、許容するスキーム。 */
    private static final Set<String> LOADED_SCHEMES = Set.of("http", "https", "file");

    /**
     * ID らしいパスの区切り。{@code :id} に置き換える。
     *
     * <p>置き換えないと、検査のたびに ID の違うデータで描いた同じ画面が別ページになり、
     * 違反が毎回「新規」に見える。設定の {@code pages}（{@code /runs/:id}）とも照合できない。
     */
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "\\d+"
                    + "|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|[0-9a-fA-F]{24,}"
                    + "|[0-9A-HJKMNP-TV-Z]{26}");

    static final String ID_PLACEHOLDER = ":id";

    private static final int MAX_HTML = 512;
    private static final int MAX_SUMMARY = 1024;

    private final ObjectMapper objectMapper;

    public AxeJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.AXE_JSON;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode root = read(in);
        List<JsonNode> results = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(results::add);
        } else {
            results.add(root);
        }

        Set<String> pages = new LinkedHashSet<>();
        Set<String> failedPages = new LinkedHashSet<>();
        Set<List<String>> tagFilters = new LinkedHashSet<>();
        Set<String> disabledRules = new TreeSet<>();
        boolean ruleFiltered = false;
        long needsReview = 0;
        Set<String> engines = new TreeSet<>();
        List<RawFinding> findings = new ArrayList<>();

        for (JsonNode result : results) {
            requireAxeResult(result);
            String url = result.path("url").asString();
            Optional<String> page = pagePathOf(url);
            if (page.isEmpty()) {
                // 読み込みに失敗したページの違反は、エラーページに対するものであり意味を持たない
                failedPages.add(withoutQuery(url));
                continue;
            }
            pages.add(page.get());
            engineOf(result).ifPresent(engines::add);

            JsonNode toolOptions = result.path("toolOptions");
            RuleSelection selection = ruleSelectionOf(toolOptions.path("runOnly"));
            selection.tags().ifPresent(tagFilters::add);
            ruleFiltered |= selection.byRule();
            disabledRules.addAll(disabledRulesOf(toolOptions.path("rules")));
            needsReview += countNodes(result.path("incomplete"));

            for (JsonNode violation : result.path("violations")) {
                for (JsonNode node : violation.path("nodes")) {
                    findings.add(toFinding(page.get(), violation, node, context));
                }
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pages", List.copyOf(pages));
        detail.put("failedPages", List.copyOf(failedPages));
        detail.put("tagFilters", List.copyOf(tagFilters));
        detail.put("ruleFiltered", ruleFiltered);
        detail.put("disabledRules", List.copyOf(disabledRules));
        detail.put("needsReview", needsReview);
        detail.put("engines", List.copyOf(engines));

        // 値は評価器が違反から数える。どれを重大とするかは判定基準（WCAG の版）に依存する
        RawMeasurement measurement = RawMeasurement.of(METRIC_ID, context.componentName(),
                null, "count", detail);
        return NormalizedReport.of(ArtifactType.AXE_JSON, List.of(measurement), findings);
    }

    private RawFinding toFinding(String page, JsonNode violation, JsonNode node,
                                 ParseContext context) {
        String ruleId = violation.path("id").asString("");
        String impact = node.path("impact").asString(null);
        if (impact == null || impact.isBlank()) {
            impact = violation.path("impact").asString(null);
        }
        String selector = selectorOf(node.path("target"));

        List<String> tags = new ArrayList<>();
        violation.path("tags").forEach(tag -> tags.add(tag.asString("")));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("page", page);
        detail.put("selector", selector);
        detail.put("impact", impact == null ? "unknown" : impact);
        detail.put("tags", tags);
        putIfPresent(detail, "helpUrl", violation.path("helpUrl").asString(""));
        putIfPresent(detail, "html", truncate(node.path("html").asString(""), MAX_HTML));
        putIfPresent(detail, "failureSummary",
                truncate(node.path("failureSummary").asString(""), MAX_SUMMARY));

        String help = violation.path("help").asString("");
        String title = truncate(help.isBlank() ? ruleId : help, MAX_HTML);

        // docs/initial/02-metrics-spec.md 0.4: ページパス + ルール ID + 要素の CSS セレクタ
        String identity = page + "|" + ruleId + "|" + selector;
        return new RawFinding(METRIC_ID, ruleId, severityOf(impact), title,
                null, null, context.componentName(), identity, detail);
    }

    /**
     * axe の impact を深刻度に写す。
     *
     * <p>impact の無い違反は<strong>重大側に倒す</strong>。軽微として扱うと、
     * 分類できなかったというだけで判定から外れる（fail-closed）。
     */
    static Severity severityOf(String impact) {
        if (impact == null) {
            return Severity.HIGH;
        }
        return switch (impact.toLowerCase(Locale.ROOT)) {
            case "critical" -> Severity.CRITICAL;
            case "moderate" -> Severity.MEDIUM;
            case "minor" -> Severity.LOW;
            default -> Severity.HIGH;
        };
    }

    /**
     * 検査したページのパス。読み込みに失敗したページ（{@code chrome-error://} など）は空。
     *
     * <p>クエリとフラグメントは捨てる。同じ画面が表示条件の違いで別ページに分かれるうえ、
     * URL に載ったトークンを違反の詳細として保存しかねない。ただしハッシュ方式の
     * ルーター（{@code #/orders}）ではフラグメントが画面のパスそのものなので、それを使う。
     */
    static Optional<String> pagePathOf(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !LOADED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        String path = uri.getPath();
        String fragment = uri.getFragment();
        if (fragment != null && fragment.startsWith("/")) {
            int query = fragment.indexOf('?');
            path = query < 0 ? fragment : fragment.substring(0, query);
        }
        return Optional.of(normalizePath(path));
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(ID_SEGMENT.matcher(segment).matches() ? ID_PLACEHOLDER : segment);
            }
        }
        return "/" + String.join("/", segments);
    }

    private static String withoutQuery(String url) {
        int end = url.length();
        for (char delimiter : new char[] {'?', '#'}) {
            int index = url.indexOf(delimiter);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        return url.substring(0, end);
    }

    /**
     * 要素の CSS セレクタ。{@code target} は iframe を越えるごとに要素が増え、
     * Shadow DOM の中はさらに配列になる。
     */
    private static String selectorOf(JsonNode target) {
        List<String> frames = new ArrayList<>();
        for (JsonNode frame : target) {
            if (frame.isArray()) {
                List<String> shadow = new ArrayList<>();
                frame.forEach(part -> shadow.add(part.asString("")));
                frames.add(String.join(" >>> ", shadow));
            } else {
                frames.add(frame.asString(""));
            }
        }
        return String.join(" | ", frames);
    }

    /**
     * 検査したルールの絞り込み。{@code runOnly} はタグの配列・文字列・
     * {@code {type, values}} のいずれでも書ける（axe-core API の {@code options.runOnly}）。
     */
    private static RuleSelection ruleSelectionOf(JsonNode runOnly) {
        if (runOnly.isMissingNode() || runOnly.isNull()) {
            return RuleSelection.ALL;
        }
        if (runOnly.isString()) {
            return RuleSelection.tags(List.of(runOnly.asString()));
        }
        if (runOnly.isArray()) {
            return RuleSelection.tags(texts(runOnly));
        }
        String type = runOnly.path("type").asString("").toLowerCase(Locale.ROOT);
        JsonNode values = runOnly.has("values") ? runOnly.path("values") : runOnly.path("value");
        if (type.startsWith("rule")) {
            return new RuleSelection(Optional.empty(), true);
        }
        return RuleSelection.tags(values.isArray() ? texts(values) : List.of(values.asString("")));
    }

    private static List<String> disabledRulesOf(JsonNode rules) {
        List<String> disabled = new ArrayList<>();
        for (Map.Entry<String, JsonNode> rule : rules.properties()) {
            if (rule.getValue().path("enabled").isBoolean()
                    && !rule.getValue().path("enabled").asBoolean()) {
                disabled.add(rule.getKey());
            }
        }
        return disabled;
    }

    private static long countNodes(JsonNode rules) {
        long count = 0;
        for (JsonNode rule : rules) {
            count += rule.path("nodes").size();
        }
        return count;
    }

    private static Optional<String> engineOf(JsonNode result) {
        JsonNode engine = result.path("testEngine");
        String name = engine.path("name").asString("");
        String version = engine.path("version").asString("");
        return name.isEmpty() ? Optional.empty() : Optional.of((name + " " + version).trim());
    }

    private static void requireAxeResult(JsonNode result) {
        if (!result.isObject() || !result.path("violations").isArray()
                || !result.path("url").isString()) {
            throw new ArtifactFormatException(
                    "axe-core の結果ではありません（url と violations 配列が必要です）。"
                            + " @axe-core/playwright の analyze() の戻り値を JSON で保存してください");
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString("")));
        return values.stream().sorted().toList();
    }

    private static void putIfPresent(Map<String, Object> detail, String key, String value) {
        if (!value.isBlank()) {
            detail.put(key, value);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private JsonNode read(InputStream in) {
        try {
            return objectMapper.readTree(in);
        } catch (JacksonException e) {
            throw new ArtifactFormatException(
                    "axe-core の結果 JSON を解析できませんでした: " + e.getMessage(), e);
        }
    }

    /**
     * @param tags   タグで絞り込んだ場合、そのタグ。絞り込みが無ければ空
     * @param byRule ルールを個別に指定した場合 true。どの基準を満たすか判断できない
     */
    private record RuleSelection(Optional<List<String>> tags, boolean byRule) {

        static final RuleSelection ALL = new RuleSelection(Optional.empty(), false);

        static RuleSelection tags(List<String> tags) {
            return new RuleSelection(Optional.of(tags), false);
        }
    }
}
