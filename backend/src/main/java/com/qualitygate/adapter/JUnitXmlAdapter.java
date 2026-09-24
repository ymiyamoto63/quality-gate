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
 * JUnit XML から M-08（API 契約テスト成功率）と M-11 / M-12（テスト成功率 / スキップされたテスト数）を読む
 * （docs/initial/02-metrics-spec.md M-08 / M-11）。
 *
 * <p>どちらの指標になるかは成果物の型で決まる。{@code junit-xml} は契約テスト（M-08）、
 * {@code test-junit-xml} はすべてのテスト（M-11 / M-12）。形式は同じため、読み方も同じにする。
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
 *
 * <p>M-11 の違反は失敗・エラー・不安定（再実行で成功）なテスト、M-12 の違反はスキップされたテスト。
 * スキップを M-12 に分けるのは、スキップの増加を失敗とは別の合格ラインで判定するため。
 */
@Component
public class JUnitXmlAdapter implements ArtifactAdapter {

    static final String CONTRACT_METRIC_ID = "M-08";
    static final String TEST_METRIC_ID = "M-11";
    static final String SKIPPED_METRIC_ID = "M-12";

    private static final int MAX_MESSAGE = 512;

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.JUNIT_XML || type == ArtifactType.TEST_JUNIT_XML;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        return parse(in, context, ArtifactType.JUNIT_XML);
    }

    /** 型を指定して読む。{@link ArtifactAdapter} の既定の呼び出しは M-08（契約テスト）として読む。 */
    @Override
    public NormalizedReport parse(InputStream in, ParseContext context, ArtifactType type) {
        boolean allTests = type == ArtifactType.TEST_JUNIT_XML;
        String metricId = allTests ? TEST_METRIC_ID : CONTRACT_METRIC_ID;
        Tally result = read(in, context, allTests);
        RawMeasurement measurement = RawMeasurement.of(metricId, context.componentName(),
                result.tally().successRate(), "percent", result.tally().toDetail());
        return NormalizedReport.of(type, List.of(measurement), result.findings());
    }

    private record Tally(ContractTally tally, List<RawFinding> findings) {
    }

    private static Tally read(InputStream in, ParseContext context, boolean allTests) {
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
                            findings.add(current.toFinding(outcome, context.componentName(),
                                    metricIdOf(outcome, allTests)));
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

        return new Tally(tally, findings);
    }

    /** 違反を載せる指標。すべてのテストの結果では、スキップだけを M-12 に分ける。 */
    private static String metricIdOf(Outcome outcome, boolean allTests) {
        if (!allTests) {
            return CONTRACT_METRIC_ID;
        }
        return outcome == Outcome.SKIPPED ? SKIPPED_METRIC_ID : TEST_METRIC_ID;
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

        RawFinding toFinding(Outcome result, String componentName, String metricId) {
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
            return new RawFinding(metricId, result.wire, result.severity, title, null, null,
                    componentName, className + "#" + name, detail);
        }

        private static String truncate(String value) {
            return value.length() <= MAX_MESSAGE ? value : value.substring(0, MAX_MESSAGE) + "…";
        }
    }
}
