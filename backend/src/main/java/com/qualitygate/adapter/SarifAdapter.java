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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SARIF 2.1.0 から M-06（脆弱性）を読む。
 *
 * <p>SARIF を静的解析系の第一形式としたのは、SARIF で出せるツールをすべて
 * このアダプタ 1 本に集約できるためである。Trivy / Semgrep / gitleaks / OSV などの
 * 差はここで吸収する。
 *
 * <p>深刻度は <strong>CVSS スコアを正</strong>として正規化する。ツールごとの
 * severity 表記に従うと「High」の意味がツール間で揺れ、判定の信頼性が落ちる。
 * CVSS が無い検出（SAST・シークレット混入）のみ、ツール固有の値からマッピングする。
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
                RawFinding finding = toFinding(result, rules, toolName, context);
                if (finding != null) {
                    findings.add(finding);
                }
            }
        }
        return NormalizedReport.of(ArtifactType.SARIF, List.of(), findings);
    }

    private RawFinding toFinding(JsonNode result, Map<String, JsonNode> rules,
                                 String toolName, ParseContext context) {
        String ruleId = result.path("ruleId").asString("");
        String filePath = filePathOf(result);
        if (context.isExcluded(filePath)) {
            return null;
        }

        JsonNode rule = rules.getOrDefault(ruleId, objectMapper.nullNode());
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

        return new RawFinding("M-06", ruleId, severity, title, filePath, lineOf(result),
                context.componentName(), identity, detail);
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
