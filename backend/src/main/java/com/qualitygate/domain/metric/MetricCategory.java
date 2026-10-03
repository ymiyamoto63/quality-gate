package com.qualitygate.domain.metric;

/**
 * 指標のカテゴリ（docs/metrics.md 1 章）。
 *
 * <p>宣言順が画面での表示順になる。docs/metrics.md 1 章の表と同じ並びに保つこと。
 */
public enum MetricCategory {

    FUNCTIONAL("機能テスト"),
    PERFORMANCE("性能テスト"),
    SECURITY("セキュリティ"),
    STRUCTURE("コード構造"),
    CONTRACT("契約・互換性"),
    USABILITY("使いやすさ");

    private final String displayName;

    MetricCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
