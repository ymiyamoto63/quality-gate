package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacocoXmlAdapterTest {

    private final JacocoXmlAdapter adapter = new JacocoXmlAdapter();

    private static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="quality-gate">
              <package name="com/qualitygate/evaluate">
                <class name="com/qualitygate/evaluate/BranchCoverageEvaluator"
                       sourcefilename="BranchCoverageEvaluator.java">
                  <counter type="BRANCH" missed="2" covered="18"/>
                  <counter type="LINE" missed="1" covered="40"/>
                </class>
              </package>
              <package name="com/qualitygate/evaluate/generated">
                <class name="com/qualitygate/evaluate/generated/Dto"
                       sourcefilename="Dto.java">
                  <counter type="BRANCH" missed="40" covered="0"/>
                </class>
              </package>
              <counter type="BRANCH" missed="42" covered="18"/>
            </report>
            """;

    @Test
    void クラス単位のBRANCHカウンタを集計する() {
        NormalizedReport report = adapter.parse(stream(REPORT), context(List.of()));

        RawMeasurement measurement = report.measurements().getFirst();
        assertThat(measurement.metricId()).isEqualTo("M-01");
        assertThat(measurement.unit()).isEqualTo("percent");
        // (18 + 0) / (20 + 40) = 30%
        assertThat(measurement.value()).isEqualByComparingTo("30.0000");
        assertThat(measurement.detail()).containsEntry("totalBranches", 60);
    }

    @Test
    void 除外パターンに一致するクラスを差し引いてから再計算する() {
        NormalizedReport report = adapter.parse(stream(REPORT),
                context(List.of("**/generated/**")));

        RawMeasurement measurement = report.measurements().getFirst();
        // 除外後は 18 / 20 = 90%
        assertThat(measurement.value()).isEqualByComparingTo("90.0000");
        assertThat(measurement.detail()).containsEntry("totalBranches", 20);
        assertThat(measurement.detail()).containsEntry("excludedFiles", 1);
    }

    @Test
    void ルート直下のカウンタは使わない() {
        // ルートの counter は missed=42 covered=18 だが、除外を適用できないため使わない。
        // 除外指定なしの結果がルート値と一致するのは、集計が正しいことの裏付けになる。
        NormalizedReport report = adapter.parse(stream(REPORT), context(List.of()));

        assertThat(report.measurements().getFirst().detail())
                .containsEntry("coveredBranches", 18)
                .containsEntry("totalBranches", 60);
    }

    @Test
    void 分岐が0個なら値を持たせない() {
        String noBranches = """
                <report name="x">
                  <package name="a"><class name="a/B" sourcefilename="B.java">
                    <counter type="LINE" missed="0" covered="3"/>
                  </class></package>
                </report>
                """;

        NormalizedReport report = adapter.parse(stream(noBranches), context(List.of()));

        // 100% と報告すると、分岐のないコンポーネントが合格を稼いでしまう
        assertThat(report.measurements().getFirst().value()).isNull();
    }

    @Test
    void JaCoCoのレポートでなければ理由つきで拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream("<other/>"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("<report> 要素が見つかりません");
    }

    @Test
    void 外部実体参照を展開しない() {
        // 成果物は CI から送られる信頼できない入力。XXE でファイルを読み出せてはならない。
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE report [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <report name="&xxe;">
                  <package name="a"><class name="a/B" sourcefilename="B.java">
                    <counter type="BRANCH" missed="0" covered="2"/>
                  </class></package>
                </report>
                """;

        assertThatThrownBy(() -> adapter.parse(stream(xxe), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class);
    }

    @Test
    void リポジトリ相対の除外パターンはJaCoCoのパスに一致しない() {
        // JaCoCo が報告するのは「パッケージ相対」のパス（com/qualitygate/.../Dto.java）であり、
        // リポジトリ相対（backend/src/main/java/...）ではない。
        // そのため backend/ から始まるパターンは一致しない。
        // 除外は「ツールが報告するパスの形」に合わせて書く必要がある。
        NormalizedReport report = adapter.parse(stream(REPORT),
                context(List.of("backend/**/generated/**")));

        assertThat(report.measurements().getFirst().detail())
                .containsEntry("excludedFiles", 0)
                .containsEntry("totalBranches", 60);
    }

    private static ParseContext context(List<String> exclusions) {
        return new ParseContext("backend", "head", exclusions);
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
