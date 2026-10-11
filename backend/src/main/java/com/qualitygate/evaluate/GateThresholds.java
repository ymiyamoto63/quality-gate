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
 * <p>合格ラインは指標のカテゴリ（docs/metrics.md 1 章）ごとにまとめて持つ。
 *
 * @param enabledMetrics     判定対象の指標
 * @param exclusions         計測除外の glob パターン
 * @param coverage           M-01 / M-02 の合格ライン
 * @param performance        M-03 / M-04 の合格ライン
 * @param security           M-05 / M-11 / M-12 の合格ライン
 * @param maxComplexity      M-06 の関数ごとの循環的複雑度の上限
 * @param maxBreakingChanges M-07 の合格ライン（破壊的変更の件数）
 * @param accessibility      M-08 の合格ライン
 * @param testResults        M-09 / M-10 の合格ライン
 */
// カテゴリの異なる合格ラインを 1 つの型に混ぜないため、7 を超える引数を許す
@SuppressWarnings("java:S107")
public record GateThresholds(
        Set<String> enabledMetrics,
        List<String> exclusions,
        Coverage coverage,
        Performance performance,
        Security security,
        int maxComplexity,
        int maxBreakingChanges,
        Accessibility accessibility,
        TestResults testResults) {

    /**
     * テストの網羅と質の合格ライン（docs/metrics.md M-01 / M-02）。
     *
     * @param branchMin          M-01 の合格ライン（分岐網羅率 %）
     * @param mutationScoreMin   M-02 の合格ライン（ミューテーションスコア %）
     * @param mutationComponents M-02 の対象コンポーネント。空なら限定しない
     */
    public record Coverage(BigDecimal branchMin, BigDecimal mutationScoreMin, Set<String> mutationComponents) {
    }

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
     * @param maxSkipped         M-10 スキップされたテストの件数の上限（コンポーネントごと）
     */
    public record TestResults(BigDecimal minSuccessRate, int minTestCount, int maxSkipped) {
    }

    /**
     * セキュリティの合格ライン（docs/metrics.md M-05 / M-11 / M-12）。
     *
     * @param maxCritical          M-05 の Critical の脆弱性の上限（件）
     * @param maxHigh              M-05 の High の脆弱性の上限（件）
     * @param maxSecrets           M-11 の合格ライン（シークレットの件数）
     * @param maxForbiddenLicenses M-12 の合格ライン（使用禁止ライセンスのパッケージ数）
     */
    public record Security(int maxCritical, int maxHigh, int maxSecrets, int maxForbiddenLicenses) {
    }

    /**
     * アクセシビリティの合格ライン（docs/metrics.md M-08）。
     *
     * @param maxViolations M-08 の合格ライン（critical + serious の件数）
     * @param pages         検査されているべきページ。空なら限定しない
     */
    public record Accessibility(int maxViolations, List<String> pages) {
    }


    /** 判定する指標（{@link MetricCatalog} の全指標）。 */
    public static final List<String> ALL_METRICS = MetricCatalog.all().stream()
            .map(MetricDefinition::metricId)
            .toList();

    public static GateThresholds defaults() {
        return from(QualityGateProperties.Gate.defaults());
    }

    /**
     * 合格ラインの値だけを既定値にしたもの。判定する指標・除外・対象（M-02 のコンポーネント、M-03 のシナリオ、
     * M-08 の画面）はこの設定のまま。
     *
     * <p>同じ入力をこれでも判定し、結果ごとの合格ラインを比べて「既定より緩い」かを決める（DD-27）。
     * 対象まで既定にすると結果の行の組み合わせが変わり、同じ行どうしを比べられなくなる。
     */
    public GateThresholds withDefaultLimits() {
        GateThresholds standard = defaults();
        return new GateThresholds(
                enabledMetrics,
                exclusions,
                new Coverage(standard.coverage().branchMin(), standard.coverage().mutationScoreMin(),
                        coverage.mutationComponents()),
                new Performance(standard.performance().p95Ms(), standard.performance().arrivalRateRps(),
                        standard.performance().errorRatePct(), performance.scenarios()),
                standard.security(),
                standard.maxComplexity(),
                standard.maxBreakingChanges(),
                new Accessibility(standard.accessibility().maxViolations(), accessibility.pages()),
                standard.testResults());
    }

    /** 判定しない指標（{@code QG_DISABLED_METRICS}）。指標の一覧の並び。 */
    public List<String> disabledMetrics() {
        return ALL_METRICS.stream().filter(metricId -> !enabledMetrics.contains(metricId)).toList();
    }

    /** 環境変数から判定に使う値を取り出す。判定対象は、すべての指標から無効にしたものを除いたもの。 */
    public static GateThresholds from(QualityGateProperties.Gate gate) {
        Set<String> enabled = new LinkedHashSet<>(ALL_METRICS);
        gate.disabledMetrics().forEach(enabled::remove);

        return new GateThresholds(
                Set.copyOf(enabled),
                gate.exclusions(),
                new Coverage(gate.branchCoverageMin(), gate.mutationScoreMin(),
                        Set.copyOf(gate.mutationComponents())),
                new Performance(gate.responseTimeP95MaxMs(), gate.arrivalRateRps(), gate.errorRateMaxPct(),
                        gate.perfScenarios()),
                new Security(gate.criticalVulnerabilitiesMax(), gate.highVulnerabilitiesMax(), gate.secretsMax(),
                        gate.forbiddenLicensesMax()),
                gate.complexityMax(),
                gate.breakingChangesMax(),
                new Accessibility(gate.accessibilityViolationsMax(), gate.accessibilityPages()),
                new TestResults(gate.testSuccessRateMin(), gate.testCountMin(), gate.skippedTestsMax()));
    }
}
