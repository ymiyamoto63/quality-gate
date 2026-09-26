package com.qualitygate.domain.model;

/**
 * 全指標を判定できたか（FULL）、SKIP を含むか（PARTIAL）。
 *
 * <p>PARTIAL の PASS は「測った範囲では合格」に過ぎないため、
 * ダッシュボードでは最後の FULL な Run を併せて表示する。
 */
public enum Completeness {
    FULL,
    PARTIAL
}
