package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.ContractTally;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit XML から M-08（API 契約テスト成功率）を読む（docs/02-metrics-spec.md M-08）。
 *
 * <p>Surefire / Failsafe と Vitest の junit reporter の出力を受け付ける。ルートは
 * {@code <testsuites>} でも {@code <testsuite>} でもよい。
 *
 * <p>{@code <testsuite>} の {@code tests} / {@code failures} 属性は使わず、
 * <strong>{@code <testcase>} を 1 件ずつ数え直す</strong>。属性はツールによって
 * スキップを含む・含まないが分かれ、再実行（{@code rerunFailingTestsCount}）の扱いも揃わない。
 *
 * <p>送られたテストが契約テストであるかは CI 側の責務とする（どのレポートを送るかで決まる）。
 * ここでは単体テストと契約テストを見分けない。
 */
@Component
public class JUnitXmlAdapter implements ArtifactAdapter {

    static final String METRIC_ID = "M-08";

    private static final int MAX_MESSAGE = 512;

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.JUNIT_XML;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        ContractTally tally = ContractTally.EMPTY;
        List<RawFinding> findings = new ArrayList<>();

        XMLStreamReader xml = SafeXml.reader(in);
        try {
            boolean sawRoot = false;
            TestCase current = null;
            StringBuilder text = null;

            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String element = xml.getLocalName();
                    if (!sawRoot) {
                        requireRoot(element);
                        sawRoot = true;
                    } else if ("testcase".equals(element)) {
                        current = new TestCase(xml.getAttributeValue(null, "classname"),
                                xml.getAttributeValue(null, "name"));
                    } else if (current != null && current.accepts(element)) {
                        current.outcome(element, xml.getAttributeValue(null, "message"),
                                xml.getAttributeValue(null, "type"));
                        text = new StringBuilder();
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) && text != null) {
                    if (text.length() < MAX_MESSAGE) {
                        text.append(xml.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = xml.getLocalName();
                    if (current != null && current.accepts(element) && text != null) {
                        current.body(element, text.toString());
                        text = null;
                    } else if ("testcase".equals(element) && current != null) {
                        Outcome outcome = current.result();
                        tally = tally.plus(outcome.wire);
                        if (outcome != Outcome.PASSED) {
                            findings.add(current.toFinding(outcome, context.componentName()));
                        }
                        current = null;
                    }
                }
            }
            if (!sawRoot) {
                requireRoot(null);
            }
        } catch (XMLStreamException e) {
            throw new ArtifactFormatException(
                    "JUnit XML の解析に失敗しました: " + e.getMessage(), e);
        } finally {
            close(xml);
        }

        RawMeasurement measurement = RawMeasurement.of(METRIC_ID, context.componentName(),
                tally.successRate(), "percent", tally.toDetail());
        return NormalizedReport.of(ArtifactType.JUNIT_XML, List.of(measurement), findings);
    }

    private static void requireRoot(String element) {
        if (!"testsuites".equals(element) && !"testsuite".equals(element)) {
            throw new ArtifactFormatException(
                    "JUnit XML ではありません（<testsuites> または <testsuite> 要素が見つかりません）。"
                            + " Surefire / Failsafe の TEST-*.xml か、Vitest の junit reporter の出力を"
                            + "送信してください");
        }
    }

    private static void close(XMLStreamReader xml) {
        try {
            xml.close();
        } catch (XMLStreamException e) {
            // 読み終えた後のクローズ失敗は結果に影響しない
        }
    }

    /**
     * テスト 1 件の結果。子要素の組み合わせで決まる。
     *
     * <p>重い順に {@code error} → {@code failure} → {@code skipped} → {@code flakyFailure}
     * / {@code flakyError}（再実行で成功した）→ 成功。Surefire は再実行しても失敗し続けたテストに
     * {@code failure} と {@code rerunFailure} の両方を付けるため、{@code rerunFailure} 単独では判断しない。
     */
    enum Outcome {
        ERRORED("errored", Severity.HIGH),
        FAILED("failed", Severity.HIGH),
        SKIPPED("skipped", Severity.INFO),
        FLAKY("flaky", Severity.LOW),
        PASSED("passed", Severity.INFO);

        final String wire;
        final Severity severity;

        Outcome(String wire, Severity severity) {
            this.wire = wire;
            this.severity = severity;
        }
    }

    private static final class TestCase {
        private final String className;
        private final String name;
        private Outcome outcome = Outcome.PASSED;
        private String message;
        private String type;

        TestCase(String className, String name) {
            this.className = className == null ? "" : className.strip();
            this.name = name == null ? "" : name.strip();
        }

        boolean accepts(String element) {
            return outcomeOf(element) != null;
        }

        void outcome(String element, String elementMessage, String elementType) {
            Outcome candidate = outcomeOf(element);
            // 列挙の宣言順が重さの順。軽い結果で重い結果を上書きしない
            if (candidate.ordinal() < outcome.ordinal()) {
                outcome = candidate;
                message = elementMessage;
                type = elementType;
            }
        }

        void body(String element, String text) {
            if (outcomeOf(element) == outcome && (message == null || message.isBlank())) {
                message = text;
            }
        }

        Outcome result() {
            return outcome;
        }

        private static Outcome outcomeOf(String element) {
            return switch (element) {
                case "error" -> Outcome.ERRORED;
                case "failure" -> Outcome.FAILED;
                case "skipped" -> Outcome.SKIPPED;
                case "flakyFailure", "flakyError" -> Outcome.FLAKY;
                default -> null;
            };
        }

        RawFinding toFinding(Outcome result, String componentName) {
            String label = className.isEmpty() ? name : className + "." + name;
            String title = switch (result) {
                case ERRORED -> label + " がエラーで終了しました";
                case FAILED -> label + " が失敗しました";
                case SKIPPED -> label + " はスキップされました";
                case FLAKY -> label + " は再実行で成功しました（不安定）";
                case PASSED -> label;
            };
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("testClass", className);
            detail.put("testName", name);
            detail.put("outcome", result.wire);
            if (type != null && !type.isBlank()) {
                detail.put("failureType", type.strip());
            }
            if (message != null && !message.isBlank()) {
                detail.put("message", truncate(message.strip()));
            }
            // テストクラス名はファイルパスではない。リンクを組み立てると壊れたリンクになる
            return new RawFinding(METRIC_ID, result.wire, result.severity, title, null, null,
                    componentName, className + "#" + name, detail);
        }

        private static String truncate(String value) {
            return value.length() <= MAX_MESSAGE ? value : value.substring(0, MAX_MESSAGE) + "…";
        }
    }
}
