package com.qualitygate.config;

import com.qualitygate.domain.gate.ConfigValidationError;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.gate.GateConfigDocument;
import com.qualitygate.domain.model.WcagStandard;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code .quality-gate.yml} を検証して {@link GateConfigDocument} にする。
 *
 * <p><strong>未知のキーはエラーにする。</strong>typo を黙って無視すると、
 * 設定したつもりの値が効かないまま合格が出続ける。
 */
@Component
public class GateConfigParser {

    private static final int SUPPORTED_VERSION = 1;

    private static final Set<String> ROOT_KEYS = Set.of(
            "version", "execution", "exclusions", "metrics");

    private static final Set<String> EXECUTION_KEYS = Set.of("skippable_metrics");

    /** 指標名（YAML のキー）と、それぞれに書ける項目。 */
    private static final Map<String, Set<String>> METRIC_KEYS = Map.ofEntries(
            Map.entry("branch_coverage", Set.of("enabled", "threshold", "warn_below")),
            Map.entry("mutation_score", Set.of("enabled", "threshold", "components")),
            Map.entry("performance", Set.of("enabled", "p95_ms", "arrival_rate_rps", "error_rate_pct",
                    "scenarios")),
            Map.entry("vulnerabilities", Set.of("enabled", "max_critical", "max_high")),
            Map.entry("cyclomatic_complexity", Set.of("enabled", "max_complexity", "warn_from")),
            Map.entry("api_contract", Set.of("enabled", "breaking_changes")),
            Map.entry("accessibility", Set.of("enabled", "standard", "max_critical", "pages")),
            Map.entry("test_results", Set.of("enabled", "min_success_rate", "min_test_count",
                    "max_skipped", "max_skipped_increase")),
            Map.entry("secrets", Set.of("enabled", "max_secrets")),
            Map.entry("licenses", Set.of("enabled", "max_forbidden", "max_restricted", "max_unknown")),
            // 参考値の指標。合格ラインを持たないため、書けるのは enabled だけ
            Map.entry("duplication", Set.of("enabled")),
            Map.entry("lighthouse", Set.of("enabled")),
            Map.entry("bundle_size", Set.of("enabled")));

    public GateConfigDocument parse(String yaml) {
        YamlLineIndex lines = YamlLineIndex.of(yaml);
        List<ConfigValidationError> errors = new ArrayList<>();

        Map<String, Object> root = load(yaml, errors);
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }

        checkUnknownKeys(root, ROOT_KEYS, "", lines, errors);
        int version = versionOf(root, lines, errors);

        GateConfigDocument.Execution execution = executionOf(root, lines, errors);
        List<String> exclusions = stringList(root.get("exclusions"));
        Map<String, GateConfigDocument.MetricConfig> metrics = metricsOf(root, lines, errors);

        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new GateConfigDocument(version, execution, exclusions, metrics);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(String yaml, List<ConfigValidationError> errors) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);   // 同じキーを 2 度書いたら後勝ちで黙らせない
        options.setCodePointLimit(1024 * 1024);
        try {
            Object loaded = new Yaml(new SafeConstructor(options)).load(yaml);
            if (loaded == null) {
                errors.add(ConfigValidationError.at(1, "", "設定ファイルが空です"));
                return Map.of();
            }
            if (!(loaded instanceof Map)) {
                errors.add(ConfigValidationError.at(1, "",
                        "トップレベルはマッピング（key: value）である必要があります"));
                return Map.of();
            }
            return (Map<String, Object>) loaded;
        } catch (MarkedYAMLException e) {
            errors.add(ConfigValidationError.at(e.getProblemMark().getLine() + 1, "",
                    "YAML として解析できません: " + e.getProblem()));
            return Map.of();
        } catch (RuntimeException e) {
            errors.add(ConfigValidationError.at(null, "",
                    "YAML として解析できません: " + e.getMessage()));
            return Map.of();
        }
    }

    private int versionOf(Map<String, Object> root, YamlLineIndex lines,
                          List<ConfigValidationError> errors) {
        Object value = root.get("version");
        if (value == null) {
            errors.add(error(lines, "version", "version は必須です（現在の対応版は 1 です）"));
            return SUPPORTED_VERSION;
        }
        if (!(value instanceof Integer version) || version != SUPPORTED_VERSION) {
            errors.add(error(lines, "version",
                    "対応していない version です: %s（対応版は %d）"
                            .formatted(value, SUPPORTED_VERSION)));
            return SUPPORTED_VERSION;
        }
        return version;
    }

    private GateConfigDocument.Execution executionOf(Map<String, Object> root,
                                                     YamlLineIndex lines,
                                                     List<ConfigValidationError> errors) {
        GateConfigDocument.Execution fallback = GateConfigDocument.defaults().execution();
        Map<String, Object> execution = mapOf(root.get("execution"));
        if (execution.isEmpty()) {
            return fallback;
        }
        checkUnknownKeys(execution, EXECUTION_KEYS, "execution", lines, errors);

        Set<String> skippable = new LinkedHashSet<>(stringList(execution.get("skippable_metrics")));
        for (String metric : skippable) {
            if (!METRIC_KEYS.containsKey(metric)) {
                errors.add(error(lines, "execution.skippable_metrics",
                        "未知の指標名です: %s（指定できるのは %s）"
                                .formatted(metric, String.join(", ", sorted(METRIC_KEYS.keySet())))));
            }
        }

        return new GateConfigDocument.Execution(skippable);
    }

    private Map<String, GateConfigDocument.MetricConfig> metricsOf(
            Map<String, Object> root, YamlLineIndex lines,
            List<ConfigValidationError> errors) {

        Map<String, GateConfigDocument.MetricConfig> result =
                new LinkedHashMap<>(GateConfigDocument.defaults().metrics());
        Map<String, Object> metrics = mapOf(root.get("metrics"));
        checkUnknownKeys(metrics, METRIC_KEYS.keySet(), "metrics", lines, errors);

        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            Set<String> allowed = METRIC_KEYS.get(entry.getKey());
            if (allowed == null) {
                continue;   // 未知のキーは上で報告済み
            }
            String path = "metrics." + entry.getKey();
            Map<String, Object> values = mapOf(entry.getValue());
            checkUnknownKeys(values, allowed, path, lines, errors);
            validateNumbers(values, path, lines, errors);
            if ("mutation_score".equals(entry.getKey())) {
                validateMutation(values, path, lines, errors);
            }
            if ("accessibility".equals(entry.getKey())) {
                validateAccessibility(values, path, lines, errors);
            }
            if ("test_results".equals(entry.getKey())) {
                validateTestResults(values, path, lines, errors);
            }

            boolean enabled = !Boolean.FALSE.equals(values.get("enabled"));
            result.put(entry.getKey(), new GateConfigDocument.MetricConfig(enabled, values));
        }
        return result;
    }

    /**
     * M-02 の対象コンポーネント。
     *
     * <p>{@code components} を文字列 1 つで書かれたまま読み流すと「限定なし」になり、
     * frontend まで判定対象に入って ERROR が並ぶ。書いた意図と逆に効くため拒否する。
     */
    private void validateMutation(Map<String, Object> values, String path, YamlLineIndex lines,
                                  List<ConfigValidationError> errors) {
        Object components = values.get("components");
        boolean listOfNames = components instanceof List<?> list
                && list.stream().allMatch(c -> c instanceof String s && !s.isBlank());
        if (components != null && !listOfNames) {
            errors.add(error(lines, path + ".components",
                    "コンポーネント名の配列で指定してください（例: [backend]。受信値: %s）"
                            .formatted(quote(components))));
        }
    }

    /**
     * M-10 の判定基準と検査対象ページ。
     *
     * <p>未知の基準を既定値で読み流すと、書いた基準とは違う基準で合否が出る。
     * ページは画面のパス（{@code /runs/:id}）で書く。URL で書くと検査結果と照合できない。
     */
    private void validateAccessibility(Map<String, Object> values, String path,
                                       YamlLineIndex lines, List<ConfigValidationError> errors) {
        Object standard = values.get("standard");
        Set<String> standards = Arrays.stream(WcagStandard.values()).map(WcagStandard::wire)
                .collect(Collectors.toSet());
        if (standard != null && !standards.contains(String.valueOf(standard))) {
            errors.add(error(lines, path + ".standard", "指定できるのは %s のいずれかです（受信値: %s）"
                    .formatted(String.join(" / ", sorted(standards)), quote(standard))));
        }
        Object pages = values.get("pages");
        boolean listOfPaths = pages instanceof List<?> list
                && list.stream().allMatch(p -> p instanceof String s && s.startsWith("/"));
        if (pages != null && !listOfPaths) {
            errors.add(error(lines, path + ".pages",
                    "/ で始まる画面のパスの配列で指定してください（例: [\"/login\", \"/runs/:id\"]。受信値: %s）"
                            .formatted(quote(pages))));
        }
    }

    /**
     * M-11 の最小実行件数。
     *
     * <p>0 を許すと、テストが 1 件も動かなかった Run が合格になる。
     * 「検証していない」を「すべて成功」と読み違える設定は受け付けない。
     */
    private void validateTestResults(Map<String, Object> values, String path, YamlLineIndex lines,
                                  List<ConfigValidationError> errors) {
        Object minTestCount = values.get("min_test_count");
        if (minTestCount instanceof Number number
                && new BigDecimal(number.toString()).compareTo(BigDecimal.ONE) < 0) {
            errors.add(error(lines, path + ".min_test_count",
                    "1 以上を指定してください。実行 0 件は合格にしません（受信値: %s）"
                            .formatted(number)));
        }
    }

    /** 割合は 0〜100、件数は 0 以上。範囲外を通すと、判定が意図せず緩くなる。 */
    private void validateNumbers(Map<String, Object> values, String path, YamlLineIndex lines,
                                 List<ConfigValidationError> errors) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null || "enabled".equals(key) || value instanceof Boolean) {
                continue;
            }
            boolean percentage = key.endsWith("threshold") || key.endsWith("_rate_pct")
                    || key.equals("min_success_rate");
            boolean count = key.startsWith("max_") || key.startsWith("min_test")
                    || key.equals("warn_from") || key.equals("breaking_changes")
                    || key.equals("p95_ms") || key.equals("arrival_rate_rps");
            if (!percentage && !count) {
                continue;
            }
            if (!(value instanceof Number number)) {
                errors.add(error(lines, path + "." + key,
                        "数値を指定してください（受信値: %s）".formatted(quote(value))));
                continue;
            }
            BigDecimal decimal = new BigDecimal(number.toString());
            if (percentage && (decimal.signum() < 0 || decimal.compareTo(BigDecimal.valueOf(100)) > 0)) {
                errors.add(error(lines, path + "." + key,
                        "0〜100 の数値を指定してください（受信値: %s）".formatted(decimal)));
            } else if (count && decimal.signum() < 0) {
                errors.add(error(lines, path + "." + key,
                        "0 以上の数値を指定してください（受信値: %s）".formatted(decimal)));
            }
        }
    }

    /**
     * 未知のキーを報告する。似た名前の候補を添えるのは、typo を自力で直せるようにするため。
     */
    private void checkUnknownKeys(Map<String, Object> actual, Set<String> allowed, String path,
                                  YamlLineIndex lines, List<ConfigValidationError> errors) {
        for (String key : actual.keySet()) {
            if (allowed.contains(key)) {
                continue;
            }
            String childPath = path.isEmpty() ? key : path + "." + key;
            String suggestion = closest(key, allowed)
                    .map(candidate -> "。'%s' の誤りではありませんか".formatted(candidate))
                    .orElse("");
            errors.add(error(lines, childPath, "未知のキーです" + suggestion));
        }
    }

    /** 編集距離が近いものを候補にする。遠いものを出すと、かえって迷わせる。 */
    static Optional<String> closest(String key, Set<String> candidates) {
        return candidates.stream()
                .map(candidate -> Map.entry(candidate, distance(key, candidate)))
                .filter(entry -> entry.getValue() <= Math.max(2, key.length() / 3))
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            System.arraycopy(current, 0, previous, 0, current.length);
        }
        return previous[b.length()];
    }

    private static ConfigValidationError error(YamlLineIndex lines, String path, String message) {
        return ConfigValidationError.at(lines.lineOf(path).orElse(null), path, message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static String quote(Object value) {
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }
}
