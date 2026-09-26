package com.qualitygate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 画面のアクセシビリティ検査（M-09）で使う応答例を書き出す。
 *
 * <p>実物の API から生成する。手で書いた例を置くと、API が変わっても検査は
 * 通り続け、実際の画面だけが壊れる（{@code api/openapi.yml} と同じ扱い）。
 *
 * <p><strong>書き出す前に、実行ごとに変わる値を固定値へ置き換える。</strong>
 * UUID と評価時刻をそのまま残すと、テストを走らせるたびに差分が出て、
 * 毎回のコミットにノイズが乗る。生成物なのに「再生成して差分がないこと」を
 * 検証できなくなる。
 *
 * <p>置き換えるのは<strong>同一性が保たれる形</strong>で行う。同じ UUID は
 * 同じ固定値に、違う UUID は違う固定値に写す。画面は id で系列や行を
 * 対応づけるため、潰してしまうと検査にならない。
 */
final class FixtureWriter {

    private static final Path DIRECTORY = Path.of("..", "frontend", "e2e", "fixtures");

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * 秒未満の精度を持つ時刻は「実行時に決まった値」とみなす。
     * テストが指定する時刻（measuredAt）は秒ちょうどで書いている。
     */
    private static final Pattern VOLATILE_INSTANT = Pattern.compile(
            "\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z\"");

    /** 実行時刻の置き換え先。値そのものに意味はなく、固定であることだけが要る。 */
    private static final String FIXED_INSTANT = "\"2026-09-22T09:00:00Z\"";

    private FixtureWriter() {
    }

    static void write(String name, String json) throws IOException {
        Files.createDirectories(DIRECTORY);
        Files.writeString(DIRECTORY.resolve(name), stabilize(json), StandardCharsets.UTF_8);
    }

    static String stabilize(String json) {
        Map<String, String> assigned = new LinkedHashMap<>();
        Matcher matcher = UUID_PATTERN.matcher(json);
        StringBuilder replaced = new StringBuilder();

        while (matcher.find()) {
            String fixed = assigned.computeIfAbsent(matcher.group(), key ->
                    "00000000-0000-7000-8000-%012d".formatted(assigned.size() + 1));
            matcher.appendReplacement(replaced, fixed);
        }
        matcher.appendTail(replaced);

        return VOLATILE_INSTANT.matcher(replaced.toString()).replaceAll(FIXED_INSTANT);
    }
}
