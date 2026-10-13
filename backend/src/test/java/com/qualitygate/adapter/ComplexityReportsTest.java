package com.qualitygate.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComplexityReportsTest {

    @Test
    void 絶対パスをモジュール相対に寄せる() {
        assertThat(ComplexityReports.relativize("/work/backend/src/main/java/com/qualitygate/Good.java"))
                .isEqualTo("src/main/java/com/qualitygate/Good.java");
        assertThat(ComplexityReports.relativize("C:\\work\\web\\src\\app.ts"))
                .isEqualTo("src/app.ts");
        assertThat(ComplexityReports.relativize(null)).isNull();
    }

    /**
     * モノレポでは、モジュール相対パスのままだと GitHub のリンクが 404 になる。
     * 絶対パスに実際にコンポーネント名が含まれているときだけ接頭辞を付ける。
     */
    @Test
    void コンポーネント名が絶対パスに現れればリポジトリ相対に直す() {
        String module = "src/main/java/com/qualitygate/Good.java";

        assertThat(ComplexityReports.repoRelative(
                "/build/backend/" + module, module, "backend"))
                .isEqualTo("backend/" + module);
    }

    @Test
    void 絶対パスと合わないコンポーネント名では接頭辞を付けない() {
        String module = "src/main/java/com/qualitygate/Good.java";

        // 推測で付けると、逆に壊れたリンクを作る
        assertThat(ComplexityReports.repoRelative("/build/api/" + module, module, "backend"))
                .isEqualTo(module);
    }

    @Test
    void コンポーネント宣言が無ければモジュール相対のまま返す() {
        String module = "src/main/java/com/qualitygate/Good.java";

        assertThat(ComplexityReports.repoRelative("/build/" + module, module, null))
                .isEqualTo(module);
    }
}
