package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PmdXmlAdapterTest {

    private final PmdXmlAdapter adapter = new PmdXmlAdapter();

    private static final String PMD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <pmd version="7.0.0">
              <file name="/build/backend/src/main/java/com/qualitygate/evaluate/Big.java">
                <violation beginline="42" endline="98" rule="CyclomaticComplexity"
                           ruleset="Design" method="evaluate" priority="3">
            The method 'evaluate(EvaluationContext)' has a cyclomatic complexity of 18.
                </violation>
                <violation beginline="120" rule="CyclomaticComplexity" ruleset="Design"
                           method="small" priority="3">
            The method 'small()' has a cyclomatic complexity of 3.
                </violation>
                <violation beginline="5" rule="UnusedImports" ruleset="Best Practices">
            Avoid unused imports such as 'java.util.Set'
                </violation>
              </file>
            </pmd>
            """;

    @Test
    void CyclomaticComplexityの違反だけを読み取る() {
        NormalizedReport report = adapter.parse(stream(PMD), context(List.of()));

        // reportLevel=1 なので CC 3 の関数も出力される。判定は evaluate 側が行う。
        assertThat(report.findings()).hasSize(2);
        assertThat(report.findings()).extracting(f -> f.detail().get("complexity"))
                .containsExactly(18, 3);
    }

    @Test
    void 関数名をメッセージから取り出す() {
        NormalizedReport report = adapter.parse(stream(PMD), context(List.of()));

        assertThat(report.findings().getFirst().detail())
                .containsEntry("member", "evaluate(EvaluationContext)");
    }

    /**
     * 表示用のパスと名寄せ用のパスは別物である。
     *
     * <p>リンクが辿れるのはリポジトリ相対、base / head で一致するのはモジュール相対。
     * 片方に寄せると、リンクが 404 になるか、同じ関数が別物と見なされるかのどちらかになる。
     */
    @Test
    void 表示用のパスはリポジトリ相対にする() {
        NormalizedReport report = adapter.parse(stream(PMD), context(List.of()));

        assertThat(report.findings().getFirst().filePath())
                .isEqualTo("backend/src/main/java/com/qualitygate/evaluate/Big.java");
    }

    @Test
    void 名寄せのキーはモジュール相対にし行番号を含めない() {
        NormalizedReport report = adapter.parse(stream(PMD), context(List.of()));

        // ベース側と head 側で作業ディレクトリが違うと、同じ関数が別物と見なされる
        RawFinding finding = report.findings().getFirst();
        assertThat(finding.identity())
                .isEqualTo("src/main/java/com/qualitygate/evaluate/Big.java#evaluate(EvaluationContext)")
                .doesNotContain("42");
    }

    @Test
    void PMDのレポートでなければ理由つきで拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream("<other/>"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("<pmd> 要素が見つかりません");
    }

    @Test
    void CC値を読み取れない違反は理由つきで拒否する() {
        String broken = """
                <pmd><file name="A.java">
                  <violation beginline="1" rule="CyclomaticComplexity">壊れた本文</violation>
                </file></pmd>
                """;

        assertThatThrownBy(() -> adapter.parse(stream(broken), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("CC 値を読み取れませんでした");
    }

    private static ParseContext context(List<String> exclusions) {
        return new ParseContext("backend", "head", exclusions);
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * モノレポでは、モジュール相対パスのままだと GitHub のリンクが 404 になる。
     * 絶対パスに実際にコンポーネント名が含まれているときだけ接頭辞を付ける。
     */
    @Test
    void コンポーネント名が絶対パスに現れればリポジトリ相対に直す() {
        String module = "src/main/java/com/qualitygate/Good.java";

        assertThat(PmdXmlAdapter.repoRelative(
                "/build/backend/" + module, module, "backend"))
                .isEqualTo("backend/" + module);
    }

    @Test
    void 絶対パスと合わないコンポーネント名では接頭辞を付けない() {
        String module = "src/main/java/com/qualitygate/Good.java";

        // 推測で付けると、逆に壊れたリンクを作る
        assertThat(PmdXmlAdapter.repoRelative("/build/api/" + module, module, "backend"))
                .isEqualTo(module);
    }

    @Test
    void コンポーネント宣言が無ければモジュール相対のまま返す() {
        String module = "src/main/java/com/qualitygate/Good.java";

        assertThat(PmdXmlAdapter.repoRelative("/build/" + module, module, null))
                .isEqualTo(module);
    }
}
