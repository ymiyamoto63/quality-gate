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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * OSV-Scanner の JSON（{@code osv-scanner --format json}）から M-06（脆弱性）を読む。
 *
 * <p>{@code results[].packages[]} ごとに、脆弱性を {@code groups}（同じ脆弱性の別名をまとめたもの）の
 * 単位で 1 件とする。GHSA と CVE のように ID が違うだけの同じ脆弱性を二重に数えないため。
 *
 * <p>深刻度は SARIF と同じく <strong>CVSS スコアを正</strong>とする。{@code groups[].max_severity}
 * （CVSS のスコア）を使い、無ければ {@code database_specific.severity} の表記から写す。
 * どちらも無ければ深刻度不明（MEDIUM）とする。
 *
 * <p>名寄せのキーは SARIF の SCA と同じ（パッケージ名, 脆弱性 ID）。ID には CVE があれば CVE を使い、
 * Trivy の SARIF から OSV-Scanner に切り替えても同じ脆弱性が「新規」に見えないようにする。
 */
@Component
public class OsvJsonAdapter implements ArtifactAdapter {

    private final ObjectMapper objectMapper;

    public OsvJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.OSV_JSON;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode results = read(in).path("results");
        if (!results.isArray()) {
            throw new ArtifactFormatException(
                    "OSV-Scanner の JSON ではありません（results 配列が見つかりません）。"
                            + " osv-scanner --format json の出力を送信してください");
        }

        List<RawFinding> findings = new ArrayList<>();
        for (JsonNode result : results) {
            String sourcePath = result.path("source").path("path").asString(null);
            if (context.isExcluded(sourcePath)) {
                continue;
            }
            for (JsonNode pkg : result.path("packages")) {
                findings.addAll(findingsOf(pkg, sourcePath, context));
            }
        }
        return NormalizedReport.of(ArtifactType.OSV_JSON, List.of(), findings);
    }

    private List<RawFinding> findingsOf(JsonNode pkg, String sourcePath, ParseContext context) {
        String name = pkg.path("package").path("name").asString("");
        String version = pkg.path("package").path("version").asString("");
        String ecosystem = pkg.path("package").path("ecosystem").asString("");

        Map<String, JsonNode> vulnerabilities = new LinkedHashMap<>();
        for (JsonNode vulnerability : pkg.path("vulnerabilities")) {
            vulnerabilities.put(vulnerability.path("id").asString(""), vulnerability);
        }

        List<RawFinding> findings = new ArrayList<>();
        Set<String> grouped = new LinkedHashSet<>();
        for (JsonNode group : pkg.path("groups")) {
            List<String> ids = new ArrayList<>();
            group.path("ids").forEach(id -> ids.add(id.asString("")));
            group.path("aliases").forEach(id -> ids.add(id.asString("")));
            ids.removeIf(String::isBlank);
            if (ids.isEmpty()) {
                continue;
            }
            grouped.addAll(ids);
            JsonNode representative = ids.stream().map(vulnerabilities::get)
                    .filter(v -> v != null).findFirst().orElse(objectMapper.nullNode());
            Optional<BigDecimal> cvss = number(group.path("max_severity"));
            findings.add(toFinding(ids, representative, cvss, name, version, ecosystem,
                    sourcePath, context));
        }
        // groups を出さない古い版では、脆弱性 1 件ずつを扱う
        for (Map.Entry<String, JsonNode> entry : vulnerabilities.entrySet()) {
            if (entry.getKey().isBlank() || grouped.contains(entry.getKey())) {
                continue;
            }
            JsonNode vulnerability = entry.getValue();
            List<String> ids = new ArrayList<>(List.of(entry.getKey()));
            vulnerability.path("aliases").forEach(id -> ids.add(id.asString("")));
            ids.removeIf(String::isBlank);
            findings.add(toFinding(ids, vulnerability, Optional.empty(), name, version, ecosystem,
                    sourcePath, context));
        }
        return findings;
    }

    private static RawFinding toFinding(List<String> ids, JsonNode vulnerability,
                                        Optional<BigDecimal> cvss, String name, String version,
                                        String ecosystem, String sourcePath, ParseContext context) {
        String ruleId = ids.stream().filter(id -> id.startsWith("CVE-")).findFirst().orElse(ids.getFirst());
        Severity severity = cvss.map(Severity::fromCvss)
                .orElseGet(() -> fromLabel(vulnerability.path("database_specific").path("severity")
                        .asString("")));

        Map<String, Object> detail = new HashMap<>();
        detail.put("tool", "osv-scanner");
        detail.put("package", name);
        if (!version.isBlank()) {
            detail.put("installedVersion", version);
        }
        if (!ecosystem.isBlank()) {
            detail.put("ecosystem", ecosystem);
        }
        detail.put("aliases", List.copyOf(new LinkedHashSet<>(ids)));
        cvss.ifPresent(score -> detail.put("cvssScore", score));
        detail.put("advisoryUrl", "https://osv.dev/vulnerability/" + ids.getFirst());

        String summary = vulnerability.path("summary").asString("");
        String title = summary.isBlank()
                ? "%s %s に脆弱性 %s があります".formatted(name, version, ruleId).strip()
                : truncate(summary);
        return new RawFinding("M-06", ruleId, severity, title, sourcePath, null,
                context.componentName(), name + "|" + ruleId, detail);
    }

    /** CVSS が無い場合のフォールバック。GitHub Advisory の表記（MODERATE など）に合わせる。 */
    private static Severity fromLabel(String label) {
        return switch (label.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> Severity.CRITICAL;
            case "HIGH" -> Severity.HIGH;
            case "LOW" -> Severity.LOW;
            default -> Severity.MEDIUM;
        };
    }

    private static Optional<BigDecimal> number(JsonNode node) {
        if (node.isNumber()) {
            return Optional.of(node.decimalValue());
        }
        if (node.isString() && !node.asString().isBlank()) {
            try {
                return Optional.of(new BigDecimal(node.asString().strip()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String truncate(String value) {
        return value.length() <= 512 ? value : value.substring(0, 509) + "...";
    }

    private JsonNode read(InputStream in) {
        try {
            JsonNode root = objectMapper.readTree(in);
            if (root == null || !root.isObject()) {
                throw new ArtifactFormatException("OSV-Scanner の JSON が空、または JSON オブジェクトではありません");
            }
            return root;
        } catch (JacksonException e) {
            throw new ArtifactFormatException(
                    "OSV-Scanner の JSON を解析できませんでした: " + e.getMessage(), e);
        }
    }
}
