package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SARIF 2.1.0 から M-06（脆弱性）・M-13（シークレット）・M-14（ライセンス）を読む。
 *
 * <p>SARIF を静的解析系の第一形式としたのは、SARIF で出せるツールをすべて
 * このアダプタ 1 本に集約できるためである。Trivy / Semgrep / gitleaks / OSV などの
 * 差はここで吸収する。
 *
 * <p>深刻度は <strong>CVSS スコアを正</strong>として正規化する。ツールごとの
 * severity 表記に従うと「High」の意味がツール間で揺れ、判定の信頼性が落ちる。
 * CVSS が無い検出（SAST・シークレット混入）のみ、ツール固有の値からマッピングする。
 *
 * <p><strong>走査した対象（メタデータの {@code scanners}）が宣言されていれば</strong>、検出を指標に振り分ける。
 * シークレット（Trivy のルールの tags に {@code secret}、または gitleaks などのシークレット専用ツール）は M-13、
 * ライセンス（tags に {@code license}）は M-14、それ以外は M-06。値を与えるのも宣言した対象の指標だけにする
 * （ライセンスだけを走査した SARIF で、M-06 を「0 件」として合格にしないため）。
 *
 * <p>宣言が無い SARIF は従来どおりすべてを M-06 として読む。シークレットの分離を知らない送り手の
 * シークレットが、判定から黙って消えないようにするためである。
 */
@Component
public class SarifAdapter implements ArtifactAdapter {

    /**
     * 複雑度を報告するツール。SARIF は M-07 も運びうるため、
     * ツール名で振り分ける。判定表を定数として外に出しておき、
     * 新しいツールの追加でロジックを変えずに済むようにする。
     */
    private static final Set<String> COMPLEXITY_TOOLS = Set.of("pmd", "eslint", "lizard");

    /** シークレット混入は有効な認証情報の流出であり、常に重大として扱う。 */
    private static final Set<String> SECRET_TOOLS = Set.of("gitleaks", "trufflehog");

    static final String VULNERABILITY_METRIC = "M-06";
    static final String SECRET_METRIC = "M-13";
    static final String LICENSE_METRIC = "M-14";

    /** 宣言が無い SARIF が値を与える指標（従来どおり）。 */
    private static final Set<String> UNDECLARED_METRICS = Set.of(VULNERABILITY_METRIC, "M-07");

    /** Trivy のライセンスの分類。メッセージの {@code Classification: forbidden} から読む。 */
    private static final Pattern CLASSIFICATION =
            Pattern.compile("Classification:\\s*([A-Za-z-]+)");

    private final ObjectMapper objectMapper;

    public SarifAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.SARIF;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode root = read(in);
        JsonNode runs = root.path("runs");
        if (!runs.isArray()) {
            throw new ArtifactFormatException(
                    "SARIF ではありません（runs 配列が見つかりません）。"
                            + " SARIF 2.1.0 形式のファイルを送信してください");
        }

        Set<String> declared = declaredMetrics(context);
        List<RawFinding> findings = new ArrayList<>();
        for (JsonNode run : runs) {
            String toolName = toolNameOf(run);
            if (COMPLEXITY_TOOLS.contains(toolName)) {
                // 複雑度は専用アダプタ（PmdXmlAdapter など）で扱う。
                // ここで混ぜると M-06 の件数に複雑度違反が混入する。
                continue;
            }
            Map<String, JsonNode> rules = rulesOf(run);
            for (JsonNode result : run.path("results")) {
                RawFinding finding = toFinding(result, rules, toolName, context, declared != null);
                // 宣言していない対象の検出は捨てる（その指標は計測していないことになっている）
                if (finding != null && (declared == null || declared.contains(finding.metricId()))) {
                    findings.add(finding);
                }
            }
        }
        return NormalizedReport.of(ArtifactType.SARIF, List.of(), findings)
                .withSuppliedMetrics(declared == null ? UNDECLARED_METRICS : declared);
    }

    /** メタデータの {@code scanners} から、値を与える指標を決める。宣言が無ければ null。 */
    private static Set<String> declaredMetrics(ParseContext context) {
        List<String> scanners = context.metadataList(ParseContext.SCANNERS);
        if (scanners.isEmpty()) {
            return null;
        }
        Set<String> metrics = new LinkedHashSet<>();
        for (String scanner : scanners) {
            switch (scanner.toLowerCase(Locale.ROOT)) {
                case "vuln", "misconfig" -> metrics.add(VULNERABILITY_METRIC);
                case "secret" -> metrics.add(SECRET_METRIC);
                case "license" -> metrics.add(LICENSE_METRIC);
                default -> throw new ArtifactFormatException(
                        "メタデータの scanners に未知の値があります: %s（指定できるのは vuln / misconfig / secret / license）"
                                .formatted(scanner));
            }
        }
        return metrics;
    }

    private RawFinding toFinding(JsonNode result, Map<String, JsonNode> rules,
                                 String toolName, ParseContext context, boolean split) {
        String ruleId = result.path("ruleId").asString("");
        String filePath = filePathOf(result);
        if (context.isExcluded(filePath)) {
            return null;
        }

        JsonNode rule = rules.getOrDefault(ruleId, objectMapper.nullNode());
        String metricId = split ? metricOf(rule, toolName) : VULNERABILITY_METRIC;
        if (LICENSE_METRIC.equals(metricId)) {
            return toLicenseFinding(result, rule, ruleId, filePath, context);
        }
        Severity severity = severityOf(result, rule, toolName);
        String title = titleOf(result, rule, ruleId);

        Map<String, Object> detail = new HashMap<>();
        detail.put("tool", toolName);
        cvssOf(result, rule).ifPresent(score -> detail.put("cvssScore", score));
        packageNameOf(result).ifPresent(pkg -> detail.put("package", pkg));
        String helpUri = rule.path("helpUri").asString("");
        if (!helpUri.isEmpty()) {
            detail.put("advisoryUrl", helpUri);
        }

        // fingerprint は行番号を含めない。無関係な編集で行がずれても
        // 「新規発生」と誤判定しないため。
        String identity = identityOf(ruleId, detail, filePath);

        return new RawFinding(metricId, ruleId, severity, title, filePath, lineOf(result),
                context.componentName(), identity, detail);
    }

    /** 検出の種類。Trivy はルールの tags に secret / license / vulnerability を載せる。 */
    private static String metricOf(JsonNode rule, String toolName) {
        if (SECRET_TOOLS.contains(toolName)) {
            return SECRET_METRIC;
        }
        Set<String> tags = new HashSet<>();
        for (JsonNode tag : rule.path("properties").path("tags")) {
            tags.add(tag.asString("").toLowerCase(Locale.ROOT));
        }
        if (tags.contains("secret")) {
            return SECRET_METRIC;
        }
        if (tags.contains("license")) {
            return LICENSE_METRIC;
        }
        return VULNERABILITY_METRIC;
    }

    /**
     * ライセンスの検出。ルール ID は Trivy の {@code <パッケージ>:<ライセンス>}。
     * 分類（forbidden / restricted / reciprocal / notice / permissive / unencumbered / unknown）で判定するため、
     * CVSS ではなく分類を内訳に残す。同じパッケージに複数のライセンスが並ぶ場合の扱いは評価器が決める。
     */
    private static RawFinding toLicenseFinding(JsonNode result, JsonNode rule, String ruleId,
                                               String filePath, ParseContext context) {
        String message = result.path("message").path("text").asString("");
        int separator = ruleId.lastIndexOf(':');
        String pkg = separator > 0 ? ruleId.substring(0, separator) : ruleId;
        String license = separator > 0 ? ruleId.substring(separator + 1) : ruleId;
        Matcher matcher = CLASSIFICATION.matcher(message);
        String classification = matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "unknown";

        Map<String, Object> detail = new HashMap<>();
        detail.put("package", pkg);
        detail.put("license", license);
        detail.put("classification", classification);
        Severity severity = switch (classification) {
            case "forbidden" -> Severity.CRITICAL;
            case "restricted" -> Severity.HIGH;
            case "reciprocal", "unknown" -> Severity.MEDIUM;
            default -> Severity.INFO;
        };
        String title = "%s のライセンス %s（%s）".formatted(pkg, license, classification);
        return new RawFinding(LICENSE_METRIC, ruleId, severity, title, filePath, null,
                context.componentName(), pkg + "|" + license, detail);
    }

    /**
     * 名寄せのキー。SCA は（パッケージ名, 脆弱性 ID）で、
     * SAST は（ルール ID, ファイルパス）で同定する。
     */
    private static String identityOf(String ruleId, Map<String, Object> detail, String filePath) {
        Object pkg = detail.get("package");
        return pkg != null ? pkg + "|" + ruleId : ruleId + "|" + filePath;
    }

    private Severity severityOf(JsonNode result, JsonNode rule, String toolName) {
        if (SECRET_TOOLS.contains(toolName)) {
            return Severity.CRITICAL;
        }
        return cvssOf(result, rule)
                .map(Severity::fromCvss)
                .orElseGet(() -> fromSarifLevel(result.path("level").asString("warning")));
    }

    /**
     * CVSS スコアを探す。{@code security-severity} は GitHub Code Scanning の慣例で、
     * Trivy や Semgrep がこの形で数値を載せる。
     */
    private java.util.Optional<BigDecimal> cvssOf(JsonNode result, JsonNode rule) {
        for (JsonNode candidate : List.of(
                result.path("properties").path("cvssScore"),
                result.path("properties").path("security-severity"),
                rule.path("properties").path("cvssScore"),
                rule.path("properties").path("security-severity"))) {
            if (candidate.isNumber()) {
                return java.util.Optional.of(candidate.decimalValue());
            }
            if (candidate.isString()) {
                try {
                    return java.util.Optional.of(new BigDecimal(candidate.asString()));
                } catch (NumberFormatException e) {
                    // 数値として読めない値は無視し、次の候補を見る
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** CVSS が無い場合のフォールバック。SARIF の level から機械的に写す。 */
    private static Severity fromSarifLevel(String level) {
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "error" -> Severity.HIGH;
            case "warning" -> Severity.MEDIUM;
            case "note" -> Severity.LOW;
            default -> Severity.INFO;
        };
    }

    private static java.util.Optional<String> packageNameOf(JsonNode result) {
        for (String key : List.of("package", "packageName", "pkgName")) {
            JsonNode node = result.path("properties").path(key);
            if (node.isString() && !node.asString().isBlank()) {
                return java.util.Optional.of(node.asString());
            }
        }
        return java.util.Optional.empty();
    }

    private static String titleOf(JsonNode result, JsonNode rule, String ruleId) {
        String message = result.path("message").path("text").asString("");
        if (!message.isBlank()) {
            return truncate(message);
        }
        String shortDescription = rule.path("shortDescription").path("text").asString("");
        return truncate(shortDescription.isBlank() ? ruleId : shortDescription);
    }

    private static String filePathOf(JsonNode result) {
        return result.path("locations").path(0)
                .path("physicalLocation").path("artifactLocation").path("uri")
                .asString(null);
    }

    private static Integer lineOf(JsonNode result) {
        JsonNode line = result.path("locations").path(0)
                .path("physicalLocation").path("region").path("startLine");
        return line.isNumber() ? line.asInt() : null;
    }

    private static Map<String, JsonNode> rulesOf(JsonNode run) {
        Map<String, JsonNode> rules = new HashMap<>();
        for (JsonNode rule : run.path("tool").path("driver").path("rules")) {
            String id = rule.path("id").asString("");
            if (!id.isEmpty()) {
                rules.put(id, rule);
            }
        }
        return rules;
    }

    private static String toolNameOf(JsonNode run) {
        return run.path("tool").path("driver").path("name")
                .asString("unknown").toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value) {
        return value.length() <= 512 ? value : value.substring(0, 509) + "...";
    }

    private JsonNode read(InputStream in) {
        try {
            return objectMapper.readTree(in);
        } catch (JacksonException e) {
            throw new ArtifactFormatException(
                    "SARIF の JSON を解析できませんでした: " + e.getMessage(), e);
        }
    }
}
