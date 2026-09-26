package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JUnitXmlAdapterTest {

    private final JUnitXmlAdapter adapter = new JUnitXmlAdapter();

    @Test
    void Surefireの出力を読める() {
        // Surefire 3 が quality-gate 自身のテストに対して出力した形（properties は省略）
        NormalizedReport report = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" version="3.0.2"
                    name="com.qualitygate.RunQueryApiIT" time="0.5" tests="3" errors="0"
                    skipped="0" failures="0" flakes="0">
                  <properties>
                  </properties>
                  <testcase name="Run詳細を返す" classname="com.qualitygate.RunQueryApiIT" time="0.1"/>
                  <testcase name="違反一覧を返す" classname="com.qualitygate.RunQueryApiIT" time="0.1"/>
                  <testcase name="存在しないRunは404" classname="com.qualitygate.RunQueryApiIT" time="0.1"/>
                </testsuite>
                """);

        RawMeasurement measurement = report.measurements().getFirst();
        assertThat(measurement.metricId()).isEqualTo("M-11");
        assertThat(measurement.componentName()).isEqualTo("backend");
        assertThat(measurement.value()).isEqualByComparingTo("100");
        assertThat(measurement.detail())
                .containsEntry("executed", 3L)
                .containsEntry("passed", 3L)
                .containsEntry("failed", 0L);
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void 失敗とエラーとスキップを数え違反にする() {
        NormalizedReport report = parse("""
                <testsuites>
                  <testsuite name="src/api/client.contract.spec.ts" tests="5">
                    <testcase classname="src/api/client.contract.spec.ts" name="GET /runs/{id}"/>
                    <testcase classname="src/api/client.contract.spec.ts" name="GET /runs">
                      <failure message="expected 200 but was 500" type="AssertionError">stack</failure>
                    </testcase>
                    <testcase classname="src/api/client.contract.spec.ts" name="POST /runs">
                      <error message="connection refused" type="Error"/>
                    </testcase>
                    <testcase classname="src/api/client.contract.spec.ts" name="GET /me">
                      <skipped/>
                    </testcase>
                    <testcase classname="src/api/client.contract.spec.ts" name="GET /dashboard"/>
                  </testsuite>
                </testsuites>
                """);

        RawMeasurement measurement = report.measurements().getFirst();
        assertThat(measurement.detail())
                .containsEntry("executed", 4L)
                .containsEntry("passed", 2L)
                .containsEntry("failed", 1L)
                .containsEntry("errored", 1L)
                .containsEntry("skipped", 1L);
        // 2 / 4。スキップは分母に入れない
        assertThat(measurement.value()).isEqualByComparingTo("50");

        assertThat(report.findings()).extracting(RawFinding::ruleId)
                .containsExactly("failed", "errored", "skipped");
        RawFinding failure = report.findings().getFirst();
        assertThat(failure.severity()).isEqualTo(Severity.HIGH);
        assertThat(failure.title()).isEqualTo("src/api/client.contract.spec.ts.GET /runs が失敗しました");
        assertThat(failure.identity()).isEqualTo("src/api/client.contract.spec.ts#GET /runs");
        assertThat(failure.filePath()).isNull();
        assertThat(failure.detail())
                .containsEntry("message", "expected 200 but was 500")
                .containsEntry("failureType", "AssertionError");
        assertThat(report.findings().get(2).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void 再実行で成功したテストは成功に数え不安定として残す() {
        NormalizedReport report = parse("""
                <testsuite name="A" tests="2">
                  <testcase classname="A" name="flaky">
                    <flakyFailure message="timeout" type="java.net.SocketTimeoutException"/>
                  </testcase>
                  <testcase classname="A" name="broken">
                    <failure message="boom"/>
                    <rerunFailure message="boom"/>
                  </testcase>
                </testsuite>
                """);

        assertThat(report.measurements().getFirst().detail())
                .containsEntry("executed", 2L)
                .containsEntry("flaky", 1L)
                .containsEntry("failed", 1L);
        assertThat(report.findings()).extracting(RawFinding::ruleId)
                .containsExactly("flaky", "failed");
    }

    @Test
    void 本文だけにメッセージがあれば本文を使う() {
        NormalizedReport report = parse("""
                <testsuite name="A"><testcase classname="A" name="t">
                  <failure><![CDATA[expected <1> but was <2>]]></failure>
                </testcase></testsuite>
                """);

        assertThat(report.findings().getFirst().detail())
                .containsEntry("message", "expected <1> but was <2>");
    }

    @Test
    void テストが無ければ値を持たせない() {
        RawMeasurement measurement = parse("<testsuites/>").measurements().getFirst();

        assertThat(measurement.value()).isNull();
        assertThat(measurement.detail()).containsEntry("executed", 0L);
    }

    @Test
    void JUnitのXMLでなければ形式不正にする() {
        assertThatThrownBy(() -> parse("<mutations/>"))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("<testsuites> または <testsuite>");
        assertThatThrownBy(() -> parse("{}"))
                .isInstanceOf(ArtifactFormatException.class);
    }

    @Test
    void 外部実体参照を展開しない() {
        assertThatThrownBy(() -> parse("""
                <?xml version="1.0"?>
                <!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <testsuite name="&xxe;"/>
                """)).isInstanceOf(ArtifactFormatException.class);
    }

    @Test
    void すべてのテストの結果はM11として読みスキップだけをM12の違反にする() {
        NormalizedReport report = adapter.parse(new ByteArrayInputStream("""
                <testsuite name="src/stores/run.spec.ts" tests="4">
                  <testcase classname="src/stores/run.spec.ts" name="読み込める"/>
                  <testcase classname="src/stores/run.spec.ts" name="失敗を表示する">
                    <failure message="expected true" type="AssertionError"/>
                  </testcase>
                  <testcase classname="src/stores/run.spec.ts" name="再読み込み">
                    <skipped/>
                  </testcase>
                  <testcase classname="src/stores/run.spec.ts" name="不安定">
                    <flakyFailure message="timeout"/>
                  </testcase>
                </testsuite>
                """.getBytes(StandardCharsets.UTF_8)),
                new ParseContext("frontend", null, List.of()));

        assertThat(report.type()).isEqualTo(ArtifactType.TEST_JUNIT_XML);
        RawMeasurement measurement = report.measurements().getFirst();
        assertThat(measurement.metricId()).isEqualTo("M-11");
        assertThat(measurement.componentName()).isEqualTo("frontend");
        assertThat(measurement.detail())
                .containsEntry("executed", 3L)
                .containsEntry("failed", 1L)
                .containsEntry("skipped", 1L)
                .containsEntry("flaky", 1L);
        assertThat(report.findings()).extracting(RawFinding::metricId, RawFinding::ruleId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("M-11", "failed"),
                        org.assertj.core.groups.Tuple.tuple("M-12", "skipped"),
                        org.assertj.core.groups.Tuple.tuple("M-11", "flaky"));
    }

    private NormalizedReport parse(String xml) {
        return adapter.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                new ParseContext("backend", null, List.of()));
    }
}
