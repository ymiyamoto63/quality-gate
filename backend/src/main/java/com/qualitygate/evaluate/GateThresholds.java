package com.qualitygate.evaluate;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 合格ラインの集合。
 *
 * <p>本来はリポジトリの {@code .quality-gate.yml} から解決した {@code GateConfig} を
 * 用いる。設定解決の実装が入るまでの既定値をここに置く。
 *
 * @param enabledMetrics 判定対象の指標。Phase 1 は M-01 / M-06 / M-07 の 3 指標
 *                       （要件定義書 14 章のリリース計画に対応する）
 */
public record GateThresholds(
        Set<String> enabledMetrics,
        BigDecimal branchCoverageThreshold,
        BigDecimal branchCoverageWarnBelow,
        int maxCritical,
        int maxHigh,
        int maxComplexity,
        int complexityWarnFrom) {

    public static final String M_BRANCH_COVERAGE = "M-01";
    public static final String M_VULNERABILITIES = "M-06";
    public static final String M_COMPLEXITY = "M-07";

    public static GateThresholds defaults() {
        return new GateThresholds(
                Set.of(M_BRANCH_COVERAGE, M_VULNERABILITIES, M_COMPLEXITY),
                new BigDecimal("75"),
                new BigDecimal("80"),
                0, 0, 15, 11);
    }
}
