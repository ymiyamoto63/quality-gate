package com.qualitygate.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceLinksTest {

    private static final String REPO = "ymiyamoto63/quality-gate";
    private static final String SHA = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    @Test
    void コミットへのリンクを作る() {
        assertThat(SourceLinks.commit(REPO, SHA))
                .isEqualTo("https://github.com/" + REPO + "/commit/" + SHA);
    }

    @Test
    void 行番号つきのリンクを作る() {
        assertThat(SourceLinks.blob(REPO, SHA, "backend/src/Main.java", 42))
                .isEqualTo("https://github.com/" + REPO + "/blob/" + SHA
                        + "/backend/src/Main.java#L42");
    }

    @Test
    void 行番号が無ければファイルまでのリンクにする() {
        assertThat(SourceLinks.blob(REPO, SHA, "backend/pom.xml", null))
                .doesNotContain("#L");
    }

    /**
     * 依存パッケージの脆弱性などパスを持たない違反で、リポジトリのルートへ
     * 飛ばすリンクを作らない。行き先が違うリンクは、無いより悪い。
     */
    @Test
    void パスが無ければリンクを作らない() {
        assertThat(SourceLinks.blob(REPO, SHA, null, 10)).isNull();
        assertThat(SourceLinks.blob(REPO, SHA, "  ", 10)).isNull();
    }

    @Test
    void リポジトリが判らなければリンクを作らない() {
        assertThat(SourceLinks.commit(null, SHA)).isNull();
        assertThat(SourceLinks.blob(null, SHA, "pom.xml", null)).isNull();
    }

    @Test
    void パスの区切りを壊さずに符号化する() {
        assertThat(SourceLinks.blob(REPO, SHA, "src/main/my file.java", null))
                .endsWith("/src/main/my%20file.java");
    }
}
