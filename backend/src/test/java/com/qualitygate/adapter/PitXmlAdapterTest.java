package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PitXmlAdapterTest {

    private final PitXmlAdapter adapter = new PitXmlAdapter();

    @Test
    void 実際のPITの出力を読める() {
        // PIT 1.20 が quality-gate 自身の SourceLinks / PageCursor に対して出力したもの
        RawMeasurement measurement = parse(resource("/pit/mutations.xml"),
                context(List.of(), Map.of("mutationScope", "changed")));

        assertThat(measurement.metricId()).isEqualTo("M-02");
        assertThat(measurement.componentName()).isEqualTo("backend");
        assertThat(measurement.unit()).isEqualTo("percent");
        assertThat(measurement.detail())
                .containsEntry("killed", 27L)
                .containsEntry("survived", 1L)
                .containsEntry("totalMutations", 28L);
        // 27 / 28
        assertThat(measurement.value()).isEqualByComparingTo("96.4286");
    }

    @Test
    void PITのMutationCoverageではなくstatusを数え直して式で計算する() {
        // NO_COVERAGE は分母に含める。除くと、テストの届かない箇所が多いほど高く出る。
        // NON_VIABLE / RUN_ERROR / MEMORY_ERROR は分母から除く
        RawMeasurement measurement = parse(stream(mutations(
                "KILLED", "KILLED", "TIMED_OUT", "SURVIVED", "NO_COVERAGE",
                "NON_VIABLE", "RUN_ERROR", "MEMORY_ERROR")), context(List.of(), Map.of()));

        // (2 + 1) / (2 + 1 + 1 + 1) = 60%
        assertThat(measurement.value()).isEqualByComparingTo("60.0000");
        assertThat(measurement.detail())
                .containsEntry("timedOut", 1L)
                .containsEntry("noCoverage", 1L)
                .containsEntry("nonViable", 1L)
                .containsEntry("runError", 1L)
                .containsEntry("memoryError", 1L)
                .containsEntry("totalMutations", 8L);
    }

    @Test
    void 解析が完了しなかったmutationは黙って消さず件数に残す() {
        RawMeasurement measurement = parse(stream(mutations("KILLED", "NOT_STARTED", "STARTED")),
                context(List.of(), Map.of()));

        assertThat(measurement.detail()).containsEntry("notCompleted", 2L);
        assertThat(measurement.value()).isEqualByComparingTo("100.0000");
    }

    @Test
    void 除外パターンはJaCoCoと同じパッケージ相対のパスで照合する() {
        // M-01 と同じ exclusions を書けるようにする（docs/spec/02-metrics-spec.md 0.2）
        RawMeasurement measurement = parse(resource("/pit/mutations.xml"),
                context(List.of("com/qualitygate/platform/**"), Map.of()));

        // PageCursor の mutation（生き残った 1 件を含む）が除かれる
        assertThat(measurement.detail())
                .containsEntry("survived", 0L)
                .containsEntry("excludedFiles", 1L);
        assertThat(measurement.value()).isEqualByComparingTo("100.0000");
    }

    @Test
    void 内部クラスのmutationは外側のソースファイルで照合する() {
        String xml = """
                <mutations>
                  <mutation detected='false' status='SURVIVED'>
                    <sourceFile>Outer.java</sourceFile>
                    <mutatedClass>com.example.generated.Outer$Inner</mutatedClass>
                  </mutation>
                  <mutation detected='true' status='KILLED'>
                    <mutatedClass>com.example.Kept$1</mutatedClass>
                  </mutation>
                </mutations>
                """;

        RawMeasurement measurement = parse(stream(xml),
                context(List.of("**/generated/**"), Map.of()));

        assertThat(measurement.detail()).containsEntry("survived", 0L).containsEntry("killed", 1L);
    }

    @Test
    void ミューテーションが0個なら値を持たせない() {
        // 0% とすると不合格に、100% とすると合格を稼ぐ。どちらも事実と違う
        RawMeasurement measurement = parse(stream("<mutations partial='true'/>"),
                context(List.of(), Map.of()));

        assertThat(measurement.value()).isNull();
        assertThat(measurement.detail()).containsEntry("totalMutations", 0L);
    }

    @Test
    void 実行範囲のメタデータを計測条件として添える() {
        RawMeasurement measurement = parse(stream(mutations("KILLED")),
                context(List.of(), Map.of("mutationScope", "all")));

        assertThat(measurement.variant()).isEqualTo("all");
        assertThat(measurement.detail()).containsEntry("mutationScope", "all");
    }

    @Test
    void 実行範囲が無ければ計測条件も持たない() {
        RawMeasurement measurement = parse(stream(mutations("KILLED")),
                context(List.of(), Map.of()));

        assertThat(measurement.variant()).isNull();
        assertThat(measurement.detail()).doesNotContainKey("mutationScope");
    }

    @Test
    void PITのレポートでなければ形式不正にする() {
        assertThatThrownBy(() -> parse(stream("<report name='jacoco'/>"),
                context(List.of(), Map.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("<mutations>");
    }

    @Test
    void XMLでなければ形式不正にする() {
        assertThatThrownBy(() -> parse(stream("<html><body>PIT"), context(List.of(), Map.of())))
                .isInstanceOf(ArtifactFormatException.class);
    }

    @Test
    void 外部実体参照を展開しない() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE mutations [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <mutations><mutation status='KILLED'><sourceFile>&xxe;</sourceFile></mutation></mutations>
                """;

        assertThatThrownBy(() -> parse(stream(xml), context(List.of(), Map.of())))
                .isInstanceOf(ArtifactFormatException.class);
    }

    private RawMeasurement parse(InputStream in, ParseContext context) {
        NormalizedReport report = adapter.parse(in, context);
        assertThat(report.findings()).isEmpty();
        return report.measurements().getFirst();
    }

    private static ParseContext context(List<String> exclusions, Map<String, Object> metadata) {
        return new ParseContext("backend", "head", exclusions, metadata);
    }

    private static String mutations(String... statuses) {
        StringBuilder xml = new StringBuilder("<mutations>");
        for (String status : statuses) {
            xml.append("<mutation detected='false' status='").append(status).append("'>")
                    .append("<sourceFile>A.java</sourceFile>")
                    .append("<mutatedClass>com.example.A</mutatedClass>")
                    .append("</mutation>");
        }
        return xml.append("</mutations>").toString();
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream resource(String path) {
        return PitXmlAdapterTest.class.getResourceAsStream(path);
    }
}
