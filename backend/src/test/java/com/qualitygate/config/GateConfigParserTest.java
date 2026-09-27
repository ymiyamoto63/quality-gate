package com.qualitygate.config;

import com.qualitygate.domain.gate.ConfigValidationError;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.gate.GateConfigDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateConfigParserTest {

    private final GateConfigParser parser = new GateConfigParser();

    @Test
    void 指定した値を読み取り未指定は既定値で埋める() {
        GateConfigDocument document = parser.parse("""
                version: 1
                metrics:
                  branch_coverage:
                    threshold: 80
                """);

        assertThat(document.metric("branch_coverage").number("threshold"))
                .contains(new java.math.BigDecimal("80"));
        // 未指定の指標は既定値で埋まる。利用側が常に値がある前提で書けるようにする。
        assertThat(document.metric("vulnerabilities").number("max_critical"))
                .contains(java.math.BigDecimal.ZERO);
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
    void 未知のキーは行番号と書けるキーを添えて拒否する() {
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
                exclusions: ["a"]
                exclusions: ["b"]
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
    void 収集ランナーの対象の合格ラインを読める() {
        // collector/targets/*.gate.yml は収集ランナーが Run ごとに送る合格ライン（DD-13）。常に妥当であることを保証する。
        // どの指標を有効にするかは対象ごとに都度変えるため、中身の値は確かめない
        java.util.List<java.nio.file.Path> files;
        try (var paths = java.nio.file.Files.list(java.nio.file.Path.of("..", "collector", "targets"))) {
            files = paths.filter(p -> p.getFileName().toString().endsWith(".gate.yml")).sorted().toList();
        } catch (java.io.IOException e) {
            throw new AssertionError("収集ランナーの対象を一覧できません", e);
        }
        assertThat(files).isNotEmpty();

        for (java.nio.file.Path file : files) {
            String yaml;
            try {
                yaml = java.nio.file.Files.readString(file);
            } catch (java.io.IOException e) {
                throw new AssertionError(file + " を読めません", e);
            }
            assertThatCode(() -> parser.parse(yaml)).as(file.toString()).doesNotThrowAnyException();
        }
    }

    @Test
    void テスト結果の指標は既定で有効() {
        // テストの成功は M-09 で既定から見る
        assertThat(parser.parse("version: 1").metric("test_results").enabled()).isTrue();
    }

    @Test
    void 判定に使わない項目は未知のキーとして拒否する() {
        // 受け付けて無視すると、書いた人は効いていると思い込む
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                on_missing_report: warn
                components:
                  - name: backend
                metrics:
                  branch_coverage:
                    scope: diff
                    per_component: false
                    diff_threshold: 90
                  mutation_score:
                    scope: changed
                  vulnerabilities:
                    max_medium: 0
                  cyclomatic_complexity:
                    scope: all
                  api_contract:
                    min_success_rate: 100
                """))
                .isInstanceOf(ConfigValidationException.class)
                .satisfies(e -> assertThat(((ConfigValidationException) e).errors())
                        .extracting(ConfigValidationError::path)
                        .containsExactlyInAnyOrder("on_missing_report", "components",
                                "metrics.branch_coverage.scope", "metrics.branch_coverage.per_component",
                                "metrics.branch_coverage.diff_threshold", "metrics.mutation_score.scope",
                                "metrics.vulnerabilities.max_medium", "metrics.cyclomatic_complexity.scope",
                                "metrics.api_contract.min_success_rate"));
    }

    @Test
    void テスト結果の最小実行件数に0は指定できない() {
        assertThatThrownBy(() -> parser.parse("""
                version: 1
                metrics:
                  test_results:
                    min_test_count: 0
                    max_skipped_increase: -1
                """))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("metrics.test_results.min_test_count")
                .hasMessageContaining("metrics.test_results.max_skipped_increase");
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
                    max_forbidden: 1
                """);
        assertThat(document.metric("secrets").enabled()).isTrue();
        assertThat(document.metric("licenses").number("max_forbidden"))
                .contains(new java.math.BigDecimal("1"));
    }

    @Test
    void 廃止した参考値の指標は未知のキーとして拒否する() {
        for (String metric : java.util.List.of("duplication", "lighthouse", "bundle_size")) {
            assertThatThrownBy(() -> parser.parse("""
                    version: 1
                    metrics:
                      %s:
                        enabled: true
                    """.formatted(metric)))
                    .as(metric)
                    .isInstanceOf(ConfigValidationException.class)
                    .hasMessageContaining("metrics." + metric);
        }
    }
}
