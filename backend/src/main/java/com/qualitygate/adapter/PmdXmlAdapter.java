package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PMD XML から M-07（循環的複雑度）を読む。
 *
 * <p>ルールセットは {@code reportLevel: 1} で動かす。しきい値超過の検出ではなく
 * <strong>全メソッドの CC 値</strong>を得るためであり、判定は quality-gate 側で行う。
 * ベースコミットとの比較が必要な指標であり、超過分だけでは比較できない。
 *
 * <p>ここでは「CC 値を持つ関数の一覧」を返すだけで、新規かどうかの判定はしない。
 * 判定はベース側の一覧との比較が必要で、それは evaluate の責務である。
 */
@Component
public class PmdXmlAdapter implements ArtifactAdapter {

    /** 例: The method 'evaluate(EvaluationContext)' has a cyclomatic complexity of 18. */
    private static final Pattern COMPLEXITY =
            Pattern.compile("cyclomatic complexity of (\\d+)", Pattern.CASE_INSENSITIVE);

    /** 例: The method 'evaluate(EvaluationContext)' has ... / The class 'Foo' has ... */
    private static final Pattern MEMBER_NAME =
            Pattern.compile("The (?:method|constructor|class|operation) '([^']+)'",
                    Pattern.CASE_INSENSITIVE);

    private static final String RULE = "CyclomaticComplexity";

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.PMD_XML;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        List<RawFinding> findings = new ArrayList<>();
        XMLStreamReader xml = SafeXml.reader(in);
        boolean sawPmd = false;
        try {
            String filePath = null;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "pmd" -> sawPmd = true;
                        case "file" -> filePath = attr(xml, "name");
                        case "violation" -> {
                            RawFinding finding = toFinding(xml, filePath, context);
                            if (finding != null) {
                                findings.add(finding);
                            }
                        }
                        default -> { /* 読み飛ばす */ }
                    }
                }
            }
            if (!sawPmd) {
                throw new ArtifactFormatException(
                        "PMD のレポートではありません（<pmd> 要素が見つかりません）。"
                                + " maven-pmd-plugin の pmd.xml を送信してください");
            }
        } catch (XMLStreamException e) {
            throw new ArtifactFormatException("PMD XML の解析に失敗しました: " + e.getMessage(), e);
        } finally {
            close(xml);
        }
        return NormalizedReport.of(ArtifactType.PMD_XML, List.of(), findings);
    }

    private RawFinding toFinding(XMLStreamReader xml, String filePath, ParseContext context) {
        String rule = attr(xml, "rule");
        if (!RULE.equals(rule)) {
            return null;
        }
        String modulePath = relativize(filePath);
        if (context.isExcluded(modulePath)) {
            return null;
        }
        String repoPath = repoRelative(filePath, modulePath, context.componentName());

        // 属性は getElementText() より前にすべて読む。本文を読むとカーソルが
        // END_ELEMENT まで進み、以降は属性を取得できない。
        Integer beginLine = intAttr(xml, "beginline");
        String methodAttribute = attr(xml, "method");
        String message = text(xml);
        Integer complexity = complexityOf(message);
        if (complexity == null) {
            throw new ArtifactFormatException(
                    "CyclomaticComplexity の違反から CC 値を読み取れませんでした: " + message);
        }
        String memberName = memberNameOf(message, methodAttribute);

        // fingerprint の材料は「モジュール相対パス + 関数の同定子」。行番号を含めない。
        // リポジトリ相対ではなくモジュール相対を使うのは、base 側と head 側で
        // チェックアウト先が違っても同じ関数が同じ identity になるようにするため。
        String identity = modulePath + "#" + memberName;

        return new RawFinding("M-07", RULE, Severity.INFO,
                "%s の循環的複雑度は %d です".formatted(memberName, complexity),
                repoPath, beginLine, context.componentName(), identity,
                Map.of("complexity", complexity,
                        "member", memberName,
                        "scope", context.scope() == null ? "head" : context.scope()));
    }

    /**
     * PMD は絶対パスを出す。ベース側と head 側で作業ディレクトリが違うと
     * 同じ関数が別物と見なされるため、モジュール相対（{@code src/...} 以下）に寄せる。
     * fingerprint の安定性はこの形に依存する。
     */
    public static String relativize(String filePath) {
        if (filePath == null) {
            return null;
        }
        String normalized = filePath.replace('\\', '/');
        for (String marker : List.of("/src/main/java/", "/src/test/java/", "/src/")) {
            int index = normalized.indexOf(marker);
            if (index >= 0) {
                return normalized.substring(index + 1);
            }
        }
        return normalized;
    }

    /**
     * 表示・リンク用のリポジトリ相対パスを決める。
     *
     * <p>モジュール相対パス（{@code src/main/java/...}）のままでは、モノレポで
     * GitHub のリンクが 404 になる。コンポーネント名が宣言されていて、かつ
     * PMD が出した絶対パスが実際に {@code /<component>/<モジュール相対>} で
     * 終わっている場合にだけ接頭辞を付ける。推測で付けると、逆に壊れたリンクを作る。
     *
     * <p>コンポーネント宣言のない単一モジュール構成では、モジュール相対パスが
     * そのままリポジトリ相対パスである。
     */
    static String repoRelative(String absolutePath, String modulePath, String componentName) {
        if (modulePath == null || componentName == null || componentName.isBlank()) {
            return modulePath;
        }
        String normalized = absolutePath.replace('\\', '/');
        return normalized.endsWith("/" + componentName + "/" + modulePath)
                ? componentName + "/" + modulePath
                : modulePath;
    }

    static Integer complexityOf(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = COMPLEXITY.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    static String memberNameOf(String message, String methodAttribute) {
        if (message != null) {
            Matcher matcher = MEMBER_NAME.matcher(message);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return methodAttribute == null ? "(不明)" : methodAttribute;
    }

    private static String text(XMLStreamReader xml) {
        try {
            return xml.getElementText().trim();
        } catch (XMLStreamException e) {
            throw new ArtifactFormatException(
                    "violation の本文を読み取れませんでした: " + e.getMessage(), e);
        }
    }

    private static String attr(XMLStreamReader xml, String name) {
        return xml.getAttributeValue(null, name);
    }

    private static Integer intAttr(XMLStreamReader xml, String name) {
        String raw = attr(xml, name);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void close(XMLStreamReader xml) {
        try {
            xml.close();
        } catch (XMLStreamException e) {
            // 読み取り済みの結果には影響しない
        }
    }
}
