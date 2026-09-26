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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * oasdiff の JSON 出力から M-09（OpenAPI の破壊的変更件数）を読む
 * （docs/spec/02-metrics-spec.md M-09）。
 *
 * <p>受け付けるのは {@code oasdiff breaking <base> <head> --format json} の出力
 * （変更の配列）。{@code []} と {@code null} は 0 件として読む。
 * 空のファイルは 0 件とみなさない。oasdiff が動かなかったのと見分けられないためである。
 *
 * <p>oasdiff の {@code level} をそのまま深刻度に写す。何件を破壊的とみなすかは評価器が決める。
 *
 * <table>
 *   <tr><th>level</th><th>oasdiff の意味</th><th>深刻度</th></tr>
 *   <tr><td>3 / error</td><td>破壊的</td><td>HIGH</td></tr>
 *   <tr><td>2 / warning</td><td>破壊的になりうる</td><td>MEDIUM</td></tr>
 *   <tr><td>1 / info</td><td>非破壊的（changelog の出力）</td><td>INFO</td></tr>
 * </table>
 */
@Component
public class OasdiffJsonAdapter implements ArtifactAdapter {

    static final String METRIC_ID = "M-09";

    private final ObjectMapper objectMapper;

    public OasdiffJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.OASDIFF_JSON;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode root = read(in);
        if (!root.isNull() && !root.isArray()) {
            throw new ArtifactFormatException(
                    "oasdiff の出力ではありません（変更の配列ではありません）。"
                            + " oasdiff breaking <base> <head> --format json の出力を送信してください");
        }

        long breaking = 0;
        long warnings = 0;
        long informational = 0;
        List<RawFinding> findings = new ArrayList<>();
        for (JsonNode change : root) {
            requireChange(change);
            Severity severity = severityOf(change.path("level"));
            switch (severity) {
                case HIGH -> breaking++;
                case MEDIUM -> warnings++;
                default -> informational++;
            }
            findings.add(toFinding(change, severity, context));
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("breaking", breaking);
        detail.put("warnings", warnings);
        detail.put("informational", informational);
        detail.put(ParseContext.BASE_SPEC_MISSING,
                context.metadataFlag(ParseContext.BASE_SPEC_MISSING));

        RawMeasurement measurement = RawMeasurement.of(METRIC_ID, context.componentName(),
                BigDecimal.valueOf(breaking), "count", detail);
        return NormalizedReport.of(ArtifactType.OASDIFF_JSON, List.of(measurement), findings);
    }

    private static RawFinding toFinding(JsonNode change, Severity severity,
                                        ParseContext context) {
        String id = change.path("id").asString("");
        String text = change.path("text").asString(id).strip();
        String operation = change.path("operation").asString("").toUpperCase(Locale.ROOT);
        String path = change.path("path").asString("");

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("level", levelName(severity));
        putIfPresent(detail, "operation", operation);
        putIfPresent(detail, "apiPath", path);
        putIfPresent(detail, "operationId", change.path("operationId").asString(""));
        putIfPresent(detail, "section", change.path("section").asString(""));
        putIfPresent(detail, "source", change.path("source").asString(""));

        String location = operation.isEmpty() ? path : (operation + " " + path).strip();
        String title = location.isEmpty() ? text : location + ": " + text;
        // 変更の説明文を identity に含める。同じ操作に同じ種類の変更が複数あり
        // （応答のプロパティを 2 つ消した等）、説明文にだけ対象の名前が現れるため
        return new RawFinding(METRIC_ID, id, severity, title, null, null,
                context.componentName(), String.join("\u0000", id, operation, path, text),
                detail);
    }

    private static void requireChange(JsonNode change) {
        if (!change.isObject() || !change.hasNonNull("id")) {
            throw new ArtifactFormatException(
                    "oasdiff の変更として読めない要素があります（id がありません）。"
                            + " --format json で出力したものか確認してください");
        }
    }

    /** 数値（3 / 2 / 1）と文字列（error / warning / info）のどちらも受け付ける。未知は破壊的に倒す。 */
    static Severity severityOf(JsonNode level) {
        if (level.isNumber()) {
            return switch (level.asInt()) {
                case 1 -> Severity.INFO;
                case 2 -> Severity.MEDIUM;
                default -> Severity.HIGH;
            };
        }
        return switch (level.asString("").strip().toLowerCase(Locale.ROOT)) {
            case "info" -> Severity.INFO;
            case "warn", "warning" -> Severity.MEDIUM;
            // 分類できなかったというだけで判定から外さない（fail-closed）
            default -> Severity.HIGH;
        };
    }

    private static String levelName(Severity severity) {
        return switch (severity) {
            case HIGH -> "error";
            case MEDIUM -> "warning";
            default -> "info";
        };
    }

    private static void putIfPresent(Map<String, Object> detail, String key, String value) {
        if (value != null && !value.isBlank()) {
            detail.put(key, value);
        }
    }

    private JsonNode read(InputStream in) {
        JsonNode root;
        try {
            root = objectMapper.readTree(in);
        } catch (JacksonException e) {
            throw new ArtifactFormatException(
                    "oasdiff の出力を JSON として解析できませんでした: " + e.getMessage(), e);
        }
        if (root == null || root.isMissingNode()) {
            throw new ArtifactFormatException(
                    "oasdiff の出力が空です。oasdiff が正常に終了したか確認してください"
                            + "（変更が無いことを表すには [] を送信してください）");
        }
        return root;
    }
}
