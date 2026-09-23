package com.qualitygate.query;

import java.util.Locale;

/**
 * README に埋め込むバッジの SVG（FR-08-5）。shields.io の flat と同じ見た目にする。
 *
 * <p>外部のバッジサービスを使わないのは、private リポジトリの合否を外部に送らないため。
 * 文字幅はフォントを読まずに概算する（Verdana 11px の平均的な字幅）。英数字だけを載せる前提。
 */
final class BadgeSvg {

    private static final String LABEL = "quality gate";

    private BadgeSvg() {
    }

    /** 表示する状態。色は shields.io の慣例（brightgreen / yellow / red / lightgrey）に合わせる。 */
    enum Status {
        PASSING("passing", "#4c1"),
        WARNINGS("passing with warnings", "#dfb317"),
        FAILING("failing", "#e05d44"),
        UNKNOWN("unknown", "#9f9f9f");

        final String text;
        final String color;

        Status(String text, String color) {
            this.text = text;
            this.color = color;
        }
    }

    static String render(Status status) {
        int labelWidth = width(LABEL);
        int valueWidth = width(status.text);
        int total = labelWidth + valueWidth;
        return String.format(Locale.ROOT, """
                <svg xmlns="http://www.w3.org/2000/svg" width="%1$d" height="20" role="img" aria-label="%2$s: %3$s">\
                <title>%2$s: %3$s</title>\
                <linearGradient id="s" x2="0" y2="100%%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/>\
                <stop offset="1" stop-opacity=".1"/></linearGradient>\
                <clipPath id="r"><rect width="%1$d" height="20" rx="3" fill="#fff"/></clipPath>\
                <g clip-path="url(#r)"><rect width="%4$d" height="20" fill="#555"/>\
                <rect x="%4$d" width="%5$d" height="20" fill="%6$s"/>\
                <rect width="%1$d" height="20" fill="url(#s)"/></g>\
                <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">\
                <text x="%7$.1f" y="15" fill="#010101" fill-opacity=".3">%2$s</text>\
                <text x="%7$.1f" y="14">%2$s</text>\
                <text x="%8$.1f" y="15" fill="#010101" fill-opacity=".3">%3$s</text>\
                <text x="%8$.1f" y="14">%3$s</text></g></svg>
                """, total, LABEL, status.text, labelWidth, valueWidth, status.color,
                labelWidth / 2.0, labelWidth + valueWidth / 2.0).strip();
    }

    /** 文字幅の概算に左右の余白（6px ずつ）を足す。 */
    static int width(String text) {
        double width = 0;
        for (char c : text.toCharArray()) {
            width += switch (c) {
                case 'i', 'l', 'j' -> 3.5;
                case 't', 'f', 'r', ' ' -> 4.5;
                case 'm', 'w' -> 11.0;
                default -> 8.0;
            };
        }
        return (int) Math.ceil(width) + 12;
    }
}
