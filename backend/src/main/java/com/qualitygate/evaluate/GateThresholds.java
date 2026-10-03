package com.qualitygate.evaluate;

import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.MetricDefinition;
import com.qualitygate.platform.config.QualityGateProperties;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 判定に使う合格ラインの集合。環境変数（{@link QualityGateProperties.Gate}）から作る。
 *
 * <p>合否は 2 値（合格 / 不合格）。「注意」のような中間の段階は持たない。
 *
 * @param enabledMetrics    判定対象の指標
 * @param exclusions        計測除外の glob パターン
 * @param mutationComponents M-02 の対象コンポーネント。空なら限定しない
 * @param maxAccessibilityViolations M-08 の合格ライン（critical + serious の件数）
 * @param accessibilityPages         M-08 で検査されているべきページ。空なら限定しない
 * @param maxBreakingChanges         M-07 の合格ライン（破壊的変更の件数）
 * @param performance                M-03 / M-04 の合格ライン
 * @param testResults                M-09 / M-10 の合格ライン
 * @param maxSecrets                 M-11 の合格ライン（シークレットの件数）
 * @param maxForbiddenLicenses       M-12 の合格ライン（使用禁止ライセンスのパッケージ数）
 */
@SuppressWarnings("java:S107")
public record GateThresholds(
        Set<String> enabledMetrics,
        List<String> exclusions,
        BigDecimal branchCoverageThreshold,
        int maxCritical,
        int maxHigh,
        int maxComplexity,
        BigDecimal mutationThreshold,
        Set<String> mutationComponents,
        int maxAccessibilityViolations,
        List<String> accessibilityPages,
        int maxBreakingChanges,
        Performance performance,
        TestResults testResults,
        int maxSecrets,
        int maxForbiddenLicenses) {

    /**
     * 性能指標の合格ライン（docs/metrics.md M-03）。
     *
     * @param p95Ms          M-03 の合格ライン（ms 以内）。全体とシナリオの双方に適用する
     * @param arrivalRateRps 負荷条件（到達率）。実測が 95% を下回ったら計測エラー
     * @param errorRatePct   M-04 の合格ライン（% 以下）
     * @param scenarios      判定すべきシナリオ。summary に無ければ ERROR
     */
    public record Performance(BigDecimal p95Ms, BigDecimal arrivalRateRps,
                              BigDecimal errorRatePct, List<String> scenarios) {
    }

    /**
     * テスト結果の合格ライン（docs/metrics.md M-09 / M-10）。
     *
     * @param minSuccessRate     M-09 の合格ライン（成功率 %）
     * @param minTestCount       M-09 の最小実行件数。下回れば値を確定できない（ERROR）
     * @param maxSkippedIncrease M-10 の比較対象 Run からの増加の上限（件）
     */
    public record TestResults(BigDecimal minSuccessRate, int minTestCount, int maxSkippedIncrease) {
    }

    public static final String M_BRANCH_COVERAGE = "M-01";
    public static final String M_MUTATION = "M-02";
    public static final String M_PERFORMANCE_P95 = "M-03";
    public static final String M_ERROR_RATE = "M-04";
    public static final String M_VULNERABILITIES = "M-05";
    public static final String M_COMPLEXITY = "M-06";
    public static final String M_BREAKING_CHANGES = "M-07";
    public static final String M_ACCESSIBILITY = "M-08";
    public static final String M_TEST_SUCCESS = "M-09";
    public static final String M_SKIPPED_TESTS = "M-10";
    public static final String M_SECRETS = "M-11";
    public static final String M_LICENSES = "M-12";

    /** 判定する指標（{@link MetricCatalog} の全指標）。 */
    public static final List<String> ALL_METRICS = MetricCatalog.all().stream()
            .map(MetricDefinition::metricId)
            .toList();

    public static GateThresholds defaults() {
        return from(QualityGateProperties.Gate.defaults());
    }

    /** 環境変数から判定に使う値を取り出す。判定対象は、すべての指標から無効にしたものを除いたもの。 */
    public static GateThresholds from(QualityGateProperties.Gate gate) {
        Set<String> enabled = new LinkedHashSet<>(ALL_METRICS);
        gate.disabledMetrics().forEach(enabled::remove);

        return new GateThresholds(
                Set.copyOf(enabled),
                gate.exclusions(),
                gate.branchCoverageMin(),
                gate.criticalVulnerabilitiesMax(),
                gate.highVulnerabilitiesMax(),
                gate.complexityMax(),
                gate.mutationScoreMin(),
                Set.copyOf(gate.mutationComponents()),
                gate.accessibilityViolationsMax(),
                gate.accessibilityPages(),
                gate.breakingChangesMax(),
                new Performance(gate.responseTimeP95MaxMs(), gate.arrivalRateRps(), gate.errorRateMaxPct(),
                        gate.perfScenarios()),
                new TestResults(gate.testSuccessRateMin(), gate.testCountMin(), gate.skippedTestsIncreaseMax()),
                gate.secretsMax(),
                gate.forbiddenLicensesMax());
    }
}
