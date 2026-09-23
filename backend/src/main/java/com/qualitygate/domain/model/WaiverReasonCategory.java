package com.qualitygate.domain.model;

/** 免除の理由区分（docs/02-metrics-spec.md M-06「免除」）。 */
public enum WaiverReasonCategory {
    /** 脆弱なコードパスをアプリケーションが呼び出していない。推奨期限 90 日。 */
    UNREACHABLE("到達不能", 90),
    /** 検出内容が事実と異なる。推奨期限 90 日。 */
    FALSE_POSITIVE("誤検知", 90),
    /** 上流に修正版が無く、回避策を適用済み。推奨期限 30 日。 */
    NO_FIX_AVAILABLE("修正版未提供", 30),
    /** 修正の実施時期が確定している。推奨期限 30 日。 */
    PLANNED("対応計画済み", 30);

    private final String label;
    private final int recommendedDays;

    WaiverReasonCategory(String label, int recommendedDays) {
        this.label = label;
        this.recommendedDays = recommendedDays;
    }

    public String label() {
        return label;
    }

    public int recommendedDays() {
        return recommendedDays;
    }
}
