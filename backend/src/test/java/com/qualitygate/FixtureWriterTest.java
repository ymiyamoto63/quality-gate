package com.qualitygate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureWriterTest {

    @Test
    void 実行ごとに変わる値を固定値に置き換える() {
        String json = """
                {"runId":"01a0c8be-6c18-753b-91a1-29c0eaa8b3f0",\
                "evaluatedAt":"2026-09-22T10:51:57.874733Z"}""";

        String stabilized = FixtureWriter.stabilize(json);

        assertThat(stabilized).doesNotContain("01a0c8be").doesNotContain("10:51:57");
    }

    /**
     * 画面は id で系列や行を対応づける。すべて同じ値に潰すと検査にならない。
     */
    @Test
    void 同一性を保ったまま置き換える() {
        String json = """
                {"a":"01a0c8be-6c18-753b-91a1-29c0eaa8b3f0",\
                "b":"01a0c8be-6c18-753b-91a1-29c0eaa8b3f0",\
                "c":"01a0c8be-6c16-7085-91f8-143679ec587b"}""";

        String stabilized = FixtureWriter.stabilize(json);

        String a = between(stabilized, "\"a\":\"", "\"");
        String b = between(stabilized, "\"b\":\"", "\"");
        String c = between(stabilized, "\"c\":\"", "\"");

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    /** 秒ちょうどの時刻（テストが指定した measuredAt）は変えない。 */
    @Test
    void 指定された時刻は変えない() {
        String json = "{\"measuredAt\":\"2026-09-22T02:10:00Z\"}";

        assertThat(FixtureWriter.stabilize(json)).isEqualTo(json);
    }

    /** 実行ごとに変わるのは値そのものなので、秒を落とすだけでは足りない。 */
    @Test
    void 実行時刻は固定値に置き換える() {
        String first = FixtureWriter.stabilize("{\"evaluatedAt\":\"2026-09-22T10:51:57.874733Z\"}");
        String second = FixtureWriter.stabilize("{\"evaluatedAt\":\"2026-09-22T11:02:03.120099Z\"}");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 何度実行しても同じ結果になる() {
        String json = """
                {"runId":"01a0c8be-6c18-753b-91a1-29c0eaa8b3f0",\
                "evaluatedAt":"2026-09-22T10:51:57.874733Z"}""";

        assertThat(FixtureWriter.stabilize(json))
                .isEqualTo(FixtureWriter.stabilize(FixtureWriter.stabilize(json)));
    }

    private static String between(String text, String prefix, String suffix) {
        int start = text.indexOf(prefix) + prefix.length();
        return text.substring(start, text.indexOf(suffix, start));
    }
}
