package com.qualitygate.evaluate;

import com.qualitygate.domain.gate.GateConfigDocument;
import com.qualitygate.domain.model.WcagStandard;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判定に使う合格ラインの集合。解決済みの {@link GateConfigDocument} から作る。
 *
 * @param enabledMetrics    判定対象の指標
 * @param skippableMetrics  スキップ申告を受理してよい指標（指標 ID）
 * @param exclusions        計測除外の glob パターン
 * @param mutationComponents M-02 の対象コンポーネント。空なら限定しない
 * @param maxAccessibilityViolations M-10 の合格ライン（critical + serious の件数）
 * @param accessibilityStandard      M-10 の判定基準
 * @param accessibilityPages         M-10 で検査されているべきページ。空なら限定しない
 * @param contractMinSuccessRate     M-08 の合格ライン（成功率 %）
 * @param contractMinTestCount       M-08 の最小実行件数。下回れば値を確定できない（ERROR）
 * @param maxBreakingChanges         M-09 の合格ライン（破壊的変更の件数）
 * @param performance                M-03 / M-04 / M-05 の合格ライン
 * @param referenceOnlyEnvironments  ここで計測した性能値は参考値（REFERENCE）とする環境。
 *        ランナー種別（github-hosted など）または計測環境の名前で書く
 */
public record GateThresholds(
        Set<String> enabledMetrics,
        Set<String> skippableMetrics,
        List<String> exclusions,
        BigDecimal branchCoverageThreshold,
        BigDecimal branchCoverageWarnBelow,
        int maxCritical,
        int maxHigh,
        int maxComplexity,
        int complexityWarnFrom,
        BigDecimal mutationThreshold,
        Set<String> mutationComponents,
        int maxAccessibilityViolations,
        WcagStandard accessibilityStandard,
        List<String> accessibilityPages,
        BigDecimal contractMinSuccessRate,
        int contractMinTestCount,
        int maxBreakingChanges,
        Performance performance,
        Set<String> referenceOnlyEnvironments) {

    /**
     * 性能指標の合格ライン（docs/initial/02-metrics-spec.md M-03）。
     *
     * @param p95Ms          M-03 の合格ライン（ms 以内）。全体とシナリオの双方に適用する
     * @param p95WarnMs      これを超えたら WARN（既定は合格ラインの 80%）
     * @param arrivalRateRps M-04 の負荷条件（到達率）。実測が 95% を下回ったら WARN
     * @param errorRatePct   M-05 の合格ライン（% 以下）。半分を超えたら WARN
     * @param scenarios      判定すべきシナリオ。summary に無ければ ERROR
     */
    public record Performance(BigDecimal p95Ms, BigDecimal p95WarnMs, BigDecimal arrivalRateRps,
                              BigDecimal errorRatePct, List<String> scenarios) {
    }

    public static final String M_BRANCH_COVERAGE = "M-01";
    public static final String M_MUTATION = "M-02";
    public static final String M_PERFORMANCE_P95 = "M-03";
    public static final String M_THROUGHPUT = "M-04";
    public static final String M_ERROR_RATE = "M-05";
    public static final String M_VULNERABILITIES = "M-06";
    public static final String M_COMPLEXITY = "M-07";
    public static final String M_API_CONTRACT = "M-08";
    public static final String M_BREAKING_CHANGES = "M-09";
    public static final String M_ACCESSIBILITY = "M-10";

    /**
     * 判定器を実装済みの指標。
     *
     * <p>設定で有効になっていても、判定器が無い指標は評価できない。
     * 未実装の指標まで判定対象に含めると、すべての Run が ERROR で不合格になる。
     */
    public static final Set<String> IMPLEMENTED_METRICS =
            Set.of(M_BRANCH_COVERAGE, M_MUTATION, M_PERFORMANCE_P95, M_THROUGHPUT,
                    M_ERROR_RATE, M_VULNERABILITIES, M_COMPLEXITY,
                    M_API_CONTRACT, M_BREAKING_CHANGES, M_ACCESSIBILITY);

    /** YAML の指標名と指標 ID の対応。 */
    private static final Map<String, List<String>> METRIC_IDS_OF = Map.of(
            "branch_coverage", List.of(M_BRANCH_COVERAGE),
            "mutation_score", List.of(M_MUTATION),
            "performance", List.of(M_PERFORMANCE_P95, M_THROUGHPUT, M_ERROR_RATE),
            "vulnerabilities", List.of(M_VULNERABILITIES),
            "cyclomatic_complexity", List.of(M_COMPLEXITY),
            "api_contract", List.of(M_API_CONTRACT, M_BREAKING_CHANGES),
            "accessibility", List.of(M_ACCESSIBILITY));

    public static GateThresholds defaults() {
        return from(GateConfigDocument.defaults());
    }

    /**
     * 設定から判定に使う値を取り出す。
     *
     * <p>判定対象は「設定で有効」かつ「判定器が実装済み」の積集合とする。
     */
    public static GateThresholds from(GateConfigDocument document) {
        Set<String> enabled = new LinkedHashSet<>();
        document.metrics().forEach((name, config) -> {
            if (config.enabled()) {
                enabled.addAll(METRIC_IDS_OF.getOrDefault(name, List.of()));
            }
        });
        enabled.retainAll(IMPLEMENTED_METRICS);

        GateConfigDocument.MetricConfig coverage = document.metric("branch_coverage");
        GateConfigDocument.MetricConfig vulnerabilities = document.metric("vulnerabilities");
        GateConfigDocument.MetricConfig complexity = document.metric("cyclomatic_complexity");
        GateConfigDocument.MetricConfig mutation = document.metric("mutation_score");
        GateConfigDocument.MetricConfig accessibility = document.metric("accessibility");
        GateConfigDocument.MetricConfig contract = document.metric("api_contract");
        GateConfigDocument.MetricConfig performance = document.metric("performance");
        BigDecimal p95 = performance.number("p95_ms").orElse(BigDecimal.valueOf(500));

        BigDecimal threshold = coverage.number("threshold").orElse(new BigDecimal("75"));
        return new GateThresholds(
                Set.copyOf(enabled),
                skippableMetricIdsOf(document),
                document.exclusions(),
                threshold,
                coverage.number("diff_threshold").orElse(threshold.add(new BigDecimal("5"))),
                vulnerabilities.number("max_critical").orElse(BigDecimal.ZERO).intValue(),
                vulnerabilities.number("max_high").orElse(BigDecimal.ZERO).intValue(),
                complexity.number("max_complexity").orElse(BigDecimal.valueOf(15)).intValue(),
                complexity.number("warn_from").orElse(BigDecimal.valueOf(11)).intValue(),
                mutation.number("threshold").orElse(BigDecimal.valueOf(60)),
                Set.copyOf(mutation.list("components")),
                accessibility.number("max_critical").orElse(BigDecimal.ZERO).intValue(),
                accessibility.text("standard").flatMap(WcagStandard::find)
                        .orElse(WcagStandard.DEFAULT),
                List.copyOf(accessibility.list("pages")),
                contract.number("min_success_rate").orElse(BigDecimal.valueOf(100)),
                // 0 を書かれても 1 件は求める。0 件の合格は「検証していない」の言い換えにすぎない
                Math.max(1, contract.number("min_test_count").orElse(BigDecimal.ONE).intValue()),
                contract.number("breaking_changes").orElse(BigDecimal.ZERO).intValue(),
                new Performance(p95,
                        p95.multiply(new BigDecimal("0.8")),
                        performance.number("arrival_rate_rps").orElse(BigDecimal.valueOf(50)),
                        performance.number("error_rate_pct").orElse(new BigDecimal("0.1")),
                        List.copyOf(performance.list("scenarios"))),
                Set.copyOf(document.execution().referenceOnlyEnvironments()));
    }

    /** {@code execution.skippable_metrics} は指標名で書かれるため、指標 ID に直す。 */
    private static Set<String> skippableMetricIdsOf(GateConfigDocument document) {
        Set<String> ids = new LinkedHashSet<>();
        for (String name : document.execution().skippableMetrics()) {
            ids.addAll(METRIC_IDS_OF.getOrDefault(name, List.of()));
        }
        return Set.copyOf(ids);
    }
}
