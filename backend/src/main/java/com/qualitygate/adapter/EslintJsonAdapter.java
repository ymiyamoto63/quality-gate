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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ESLint の JSON（{@code eslint -f json}）から M-07（循環的複雑度）を読む。
 *
 * <p>PMD と同じく、しきい値超過の検出ではなく<strong>全関数の CC 値</strong>を得る必要がある。
 * {@code complexity} ルールを {@code ["error", 0]}（上限 0）で動かし、すべての関数を報告させる。
 * 判定（15 超か、新規・悪化か）は quality-gate 側で行う。{@code complexity} 以外のルールは読み飛ばす。
 *
 * <p>関数の同定子はメッセージの関数名（{@code Function 'load'} / {@code Method 'save'} など）。
 * 名前の無い関数（無名のアロー関数など）はファイル内の出現順で番号を振る。行番号は使わない
 * （無関係な編集による行ずれで「新規」と誤判定しないため）。
 */
@Component
public class EslintJsonAdapter implements ArtifactAdapter {

    private static final String RULE = "complexity";

    /** 例: Function 'load' has a complexity of 12. Maximum allowed is 0. */
    private static final Pattern COMPLEXITY =
            Pattern.compile("^(.*?) has a complexity of (\\d+)", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    public EslintJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.ESLINT_JSON;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode root = read(in);
        List<RawFinding> findings = new ArrayList<>();
        for (JsonNode file : root) {
            if (!file.path("filePath").isString() || !file.path("messages").isArray()) {
                throw new ArtifactFormatException(
                        "ESLint の JSON ではありません（filePath と messages を持つ要素の配列ではありません）。"
                                + " eslint -f json の出力を送信してください");
            }
            String filePath = file.path("filePath").asString();
            String modulePath = PmdXmlAdapter.relativize(filePath);
            if (context.isExcluded(modulePath)) {
                continue;
            }
            String repoPath = PmdXmlAdapter.repoRelative(filePath, modulePath, context.componentName());
            Map<String, Integer> anonymous = new HashMap<>();
            for (JsonNode message : file.path("messages")) {
                if (!RULE.equals(message.path("ruleId").asString(""))) {
                    continue;
                }
                findings.add(toFinding(message, modulePath, repoPath, anonymous, context));
            }
        }
        return NormalizedReport.of(ArtifactType.ESLINT_JSON, List.of(), findings);
    }

    private static RawFinding toFinding(JsonNode message, String modulePath, String repoPath,
                                        Map<String, Integer> anonymous, ParseContext context) {
        String text = message.path("message").asString("");
        Matcher matcher = COMPLEXITY.matcher(text);
        if (!matcher.find()) {
            throw new ArtifactFormatException(
                    "complexity の違反から CC 値を読み取れませんでした: " + text);
        }
        int complexity = Integer.parseInt(matcher.group(2));
        String member = memberOf(matcher.group(1).strip(), anonymous);
        Integer line = message.path("line").isNumber() ? message.path("line").asInt() : null;

        return new RawFinding("M-07", "CyclomaticComplexity", Severity.INFO,
                "%s の循環的複雑度は %d です".formatted(member, complexity),
                repoPath, line, context.componentName(), modulePath + "#" + member,
                Map.of("complexity", complexity,
                        "member", member,
                        "scope", context.scope() == null ? "head" : context.scope()));
    }

    /**
     * 関数の同定子。{@code Method 'save'} → {@code save}、名前の無い関数は種類と出現順（{@code Arrow function#2}）。
     */
    static String memberOf(String subject, Map<String, Integer> anonymous) {
        int quote = subject.indexOf('\'');
        int end = subject.lastIndexOf('\'');
        if (quote >= 0 && end > quote) {
            return subject.substring(quote + 1, end);
        }
        int index = anonymous.merge(subject, 1, Integer::sum);
        return subject + "#" + index;
    }

    private JsonNode read(InputStream in) {
        try {
            JsonNode root = objectMapper.readTree(in);
            if (root == null || !root.isArray()) {
                throw new ArtifactFormatException(
                        "ESLint の JSON が空、または配列ではありません。eslint -f json の出力を送信してください");
            }
            return root;
        } catch (JacksonException e) {
            throw new ArtifactFormatException(
                    "ESLint の JSON を解析できませんでした: " + e.getMessage(), e);
        }
    }
}
