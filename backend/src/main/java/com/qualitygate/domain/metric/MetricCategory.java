package com.qualitygate.domain.metric;

/**
 * 指標のカテゴリ（docs/initial/01-requirements.md 6.1 の指標一覧）。
 *
 * <p>宣言順が画面での表示順になる。要件定義の表と同じ並びに保つこと。
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
