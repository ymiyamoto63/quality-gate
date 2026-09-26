package com.qualitygate.domain.model;

import java.math.BigDecimal;

/**
 * 正規化済みの深刻度。CVSS スコアを正とし、スコアが無い検出のみ
 * ツール固有 severity からマッピングする（docs/spec/02-metrics-spec.md M-06）。
 */
public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    private static final BigDecimal CRITICAL_FLOOR = new BigDecimal("9.0");
    private static final BigDecimal HIGH_FLOOR = new BigDecimal("7.0");
    private static final BigDecimal MEDIUM_FLOOR = new BigDecimal("4.0");
    private static final BigDecimal LOW_FLOOR = new BigDecimal("0.1");

    /** CVSS v3.1 / v4 の Base Score から深刻度を決める。 */
    public static Severity fromCvss(BigDecimal score) {
        if (score == null) {
            // スコアも severity も無い場合は MEDIUM 扱いとし、UI に「深刻度不明」と示す。
            return MEDIUM;
        }
        if (score.compareTo(CRITICAL_FLOOR) >= 0) {
            return CRITICAL;
        }
        if (score.compareTo(HIGH_FLOOR) >= 0) {
            return HIGH;
        }
        if (score.compareTo(MEDIUM_FLOOR) >= 0) {
            return MEDIUM;
        }
        if (score.compareTo(LOW_FLOOR) >= 0) {
            return LOW;
        }
        return INFO;
    }

    /** M-06 の判定対象（重大・高）か。 */
    public boolean isBlocking() {
        return this == CRITICAL || this == HIGH;
    }
}
