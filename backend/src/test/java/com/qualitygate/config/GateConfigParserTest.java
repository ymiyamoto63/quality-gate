package com.qualitygate.config;

import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.gate.GateConfigDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateConfigParserTest {

    private final GateConfigParser parser = new GateConfigParser();

    @Test
    void 指定した値を読み取り未指定は既定値で埋める() {
        GateConfigDocument document = parser.parse("""
                version: 1
                enforcement: report-only
                metrics:
                  branch_coverage:
                    threshold: 80
                """);

        assertThat(document.metric("branch_coverage").number("threshold"))
                .contains(new java.math.BigDecimal("80"));
        // 未指定の指標は既定値で埋まる。利用側が常に値がある前提で書けるようにする。
        assertThat(document.metric("vulnerabilities").number("max_critical"))
                .contains(java.math.BigDecimal.ZERO);
        assertThat(document.execution().fullMeasurementIntervalDays()).isEqualTo(7);
    }

    @Test
    void versionが無ければ拒否する() {
        assertThatThrownBy(() -> parser.parse("metrics: {}"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("version は必須です");
    }

    @Test
    void 対応していないversionは拒否する() {
        assertThatThrownBy(() -> parser.parse("version: 2"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("対応していない version");
    }

    @Test
    void 未知のキーは行番号と候補つきで拒否する() {
        // typo を黙って無視すると、設定したつもりの値が効かないまま合格が出続ける
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                exclusion:
                  - "a"
                """))
                .isInstanceOf(ConfigValidationException.class)
                .satisfies(e -> {
                    var error = ((ConfigValidationException) e).errors().getFirst();
                    assertThat(error.path()).isEqualTo("exclusion");
                    assertThat(error.line()).isEqualTo(2);
                    assertThat(error.message()).contains("exclusions");
                });
    }

    @Test
    void 割合の範囲外は拒否する() {
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  branch_coverage:
                    threshold: 150
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("0〜100");
    }

    @Test
    void 件数の負値は拒否する() {
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  vulnerabilities:
                    max_high: -1
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("0 以上");
    }

    @Test
    void 文字列で書かれた数値は拒否する() {
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  branch_coverage:
                    threshold: "75%"
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("数値を指定してください");
    }

    @Test
    void 未知の指標名のスキップ許可は拒否する() {
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                execution:
                  skippable_metrics: [mutation, performance]
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("未知の指標名です: mutation");
    }

    @Test
    void 列挙値の誤りは選択肢を示して拒否する() {
        assertThatThrownBy(() -> parser.parse("version: 1\nenforcement: blocked\n"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("blocking");
    }

    @Test
    void ミューテーションの実行範囲の誤りは選択肢を示して拒否する() {
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  mutation_score:
                    scope: diff
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("all / changed");
    }

    @Test
    void ミューテーションの対象コンポーネントは配列でなければ拒否する() {
        // 文字列のまま読み流すと「限定なし」になり、書いた意図と逆に効く
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  mutation_score:
                    components: backend
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("配列");
    }

    @Test
    void アクセシビリティの基準の誤りは選択肢を示して拒否する() {
        // 既定値で読み流すと、書いた基準とは違う基準で合否が出る
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  accessibility:
                    standard: wcag22-aa
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("wcag21aa / wcag22aa");
    }

    @Test
    void 検査対象ページは画面のパスでなければ拒否する() {
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  accessibility:
                    pages: ["https://app.example.com/login"]
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("/ で始まる画面のパス");
    }

    @Test
    void 重複したキーは拒否する() {
        // 後勝ちで黙らせると、消したはずの設定が効き続ける
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                enforcement: report-only
                enforcement: blocking
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("YAML として解析できません");
    }

    @Test
    void 空の設定ファイルは拒否する() {
        assertThatThrownBy(() -> parser.parse("\n"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("空です");
    }

    @Test
    void 実際のquality_gate_ymlを読める() {
        // リポジトリ直下の .quality-gate.yml が常に妥当であることを保証する
        String yaml;
        try {
            yaml = java.nio.file.Files.readString(
                    java.nio.file.Path.of("..", ".quality-gate.yml"));
        } catch (java.io.IOException e) {
            throw new AssertionError("リポジトリの .quality-gate.yml を読めません", e);
        }

        GateConfigDocument document = parser.parse(yaml);

        assertThat(document.version()).isEqualTo(1);
        assertThat(document.execution().skippableMetrics())
                .containsExactly("mutation_score");
        // quality-gate 自身の性能は計測しない（D-17）
        assertThat(document.metrics().get("performance").enabled()).isFalse();
        assertThat(document.exclusions()).isNotEmpty();
    }

    @Test
    void 収集ランナーの対象の合格ラインを読める() {
        // collector/targets/*.gate.yml は画面（S-06）に貼り付ける控え。貼り付けて保存できる内容であることを保証する
        String yaml;
        try {
            yaml = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "..", "collector", "targets", "ymiyamoto63__like-chatgpt.gate.yml"));
        } catch (java.io.IOException e) {
            throw new AssertionError("like-chatgpt の合格ラインを読めません", e);
        }

        GateConfigDocument document = parser.parse(yaml);

        // PR の計測では M-02 と M-03〜05 をスキップする
        assertThat(document.execution().skippableMetrics())
                .containsExactly("mutation_score", "performance");
        assertThat(document.metrics().get("performance").enabled()).isTrue();
    }

    @Test
    void 契約テストの最小実行件数に0は指定できない() {
        // 0 件で合格する設定は「検証していない」を「すべて成功」と読み違える
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  api_contract:
                    min_test_count: 0
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("metrics.api_contract.min_test_count")
                .hasMessageContaining("1 以上");
    }

    @Test
    void テスト結果の指標は既定で無効で書けば有効になる() {
        // 既定で有効にすると、成果物を送っていないリポジトリの Run がすべて ERROR になる
        GateConfigDocument defaults = parser.parse("version: 1");
        assertThat(defaults.metric("test_results").enabled()).isFalse();

        GateConfigDocument document = parser.parse("""
                version: 1
                metrics:
                  test_results:
                    min_success_rate: 100
                    max_skipped_increase: 0
                """);
        assertThat(document.metric("test_results").enabled()).isTrue();
    }

    @Test
    void テスト結果の最小実行件数に0は指定できない() {
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  test_results:
                    min_test_count: 0
                    max_skipped: -1
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("metrics.test_results.min_test_count")
                .hasMessageContaining("metrics.test_results.max_skipped");
    }

    @Test
    void シークレットとライセンスの指標は既定で無効で書けば有効になる() {
        GateConfigDocument defaults = parser.parse("version: 1");
        assertThat(defaults.metric("secrets").enabled()).isFalse();
        assertThat(defaults.metric("licenses").enabled()).isFalse();

        GateConfigDocument document = parser.parse("""
                version: 1
                metrics:
                  secrets:
                    max_secrets: 0
                  licenses:
                    max_forbidden: 0
                    max_restricted: 3
                """);
        assertThat(document.metric("secrets").enabled()).isTrue();
        assertThat(document.metric("licenses").number("max_restricted"))
                .contains(new java.math.BigDecimal("3"));
    }

    @Test
    void 参考値の指標には合格ラインを書けない() {
        GateConfigDocument document = parser.parse("""
                version: 1
                metrics:
                  duplication:
                    enabled: true
                """);
        assertThat(document.metric("duplication").enabled()).isTrue();
        assertThat(parser.parse("version: 1").metric("lighthouse").enabled()).isFalse();

        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  bundle_size:
                    max_kb: 500
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("metrics.bundle_size.max_kb");
    }

    @Test
    void 編集距離が遠い候補は提示しない() {
        // 遠い候補を出すと、かえって迷わせる
        assertThat(GateConfigParser.closest("zzzzzzzz",
                java.util.Set.of("metrics", "exclusions"))).isEmpty();
        assertThat(GateConfigParser.closest("metric",
                java.util.Set.of("metrics", "exclusions"))).contains("metrics");
    }
}
