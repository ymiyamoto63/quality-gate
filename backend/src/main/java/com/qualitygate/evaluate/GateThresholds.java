package com.qualitygate.evaluate;

import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.MetricDefinition;
import com.qualitygate.domain.model.JudgementScope;
import com.qualitygate.platform.config.QualityGateProperties;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 判定に使う設定。環境変数（{@link QualityGateProperties.Gate}）から作る。
 *
 * <p>何を計測するかを絞る設定（{@link Scope}）と、合格ラインの値（{@link Limits}）に分けて持つ。
 * 「既定より緩い」かの比較（DD-27）では、対象はこの設定のまま値だけを既定にする（{@link #withDefaultLimits()}）。
 *
 * <p>合否は 2 値（合格 / 不合格）。「注意」のような中間の段階は持たない。
 *
 * @param scope  判定・計測の対象（判定する指標・除外・指標ごとの対象）
 * @param limits 合格ラインの値。指標のカテゴリ（docs/metrics.md 1 章）ごとにまとめて持つ
 */
public record GateThresholds(Scope scope, Limits limits) {

    /**
     * 何を計測・判定するかを絞る設定。合格ラインの値ではないため、既定と比べて「緩い」とは扱わない。
     *
     * @param enabledMetrics     判定対象の指標
     * @param exclusions         計測除外の glob パターン
     * @param mutationComponents M-02 の対象コンポーネント。空なら限定しない
     * @param perfScenarios      M-03 で判定すべきシナリオ。summary に無ければ ERROR
     * @param accessibilityPages M-08 で検査されているべきページ。空なら限定しない
     */
    public record Scope(Set<String> enabledMetrics, List<String> exclusions, Set<String> mutationComponents,
                        List<String> perfScenarios, List<String> accessibilityPages) {

        /** 判定しない指標（{@code QG_DISABLED_METRICS}）。指標の一覧の並び。 */
        public List<String> disabledMetrics() {
            return ALL_METRICS.stream().filter(metricId -> !enabledMetrics.contains(metricId)).toList();
        }

        /** Run に焼き付ける形（DD-27 / DD-28）。集合は並びを決めるため名前順にする。 */
        public JudgementScope toRecorded() {
            return new JudgementScope(disabledMetrics(), exclusions,
                    mutationComponents.stream().sorted().toList(), perfScenarios, accessibilityPages);
        }
    }

    /**
     * 合格ラインの値。指標のカテゴリごとにまとめる。
     *
     * @param coverage           M-01 / M-02 の合格ライン
     * @param performance        M-03 / M-04 の合格ライン
     * @param security           M-05 / M-11 / M-12 の合格ライン
     * @param maxComplexity      M-06 の関数ごとの循環的複雑度の上限
     * @param maxBreakingChanges M-07 の合格ライン（破壊的変更の件数）
     * @param accessibility      M-08 の合格ライン
     * @param testResults        M-09 / M-10 の合格ライン
     */
    public record Limits(
            Coverage coverage,
            Performance performance,
            Security security,
            int maxComplexity,
            int maxBreakingChanges,
            Accessibility accessibility,
            TestResults testResults) {
    }

    /**
     * テストの網羅と質の合格ライン（docs/metrics.md M-01 / M-02）。
     *
     * @param branchMin        M-01 の合格ライン（分岐網羅率 %）
     * @param mutationScoreMin M-02 の合格ライン（ミューテーションスコア %）
     */
    public record Coverage(BigDecimal branchMin, BigDecimal mutationScoreMin) {
    }

    /**
     * 性能指標の合格ライン（docs/metrics.md M-03 / M-04）。
     *
     * @param p95Ms          M-03 の合格ライン（ms 以内）。全体とシナリオの双方に適用する
     * @param arrivalRateRps 負荷条件（到達率）。実測が 95% を下回ったら計測エラー
     * @param errorRatePct   M-04 の合格ライン（% 以下）
     */
    public record Performance(BigDecimal p95Ms, BigDecimal arrivalRateRps, BigDecimal errorRatePct) {
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
     */
    public record Accessibility(int maxViolations) {
    }


    /** 判定する指標（{@link MetricCatalog} の全指標）。 */
    public static final List<String> ALL_METRICS = MetricCatalog.all().stream()
            .map(MetricDefinition::metricId)
            .toList();

    public static GateThresholds defaults() {
        return from(QualityGateProperties.Gate.defaults());
    }

    /**
     * 既定の合格ラインに、この設定の対象（{@link Scope}）を組み合わせたもの。
     *
     * <p>同じ入力をこれでも判定し、結果ごとの合格ラインを比べて「既定より緩い」かを決める（DD-27）。
     * 対象まで既定にすると結果の行の組み合わせが変わり、同じ行どうしを比べられなくなる。
     */
    public GateThresholds withDefaultLimits() {
        return new GateThresholds(scope, defaults().limits());
    }

    /** 環境変数から判定に使う値を取り出す。判定対象は、すべての指標から無効にしたものを除いたもの。 */
    public static GateThresholds from(QualityGateProperties.Gate gate) {
        Set<String> enabled = new LinkedHashSet<>(ALL_METRICS);
        gate.disabledMetrics().forEach(enabled::remove);

        Scope scope = new Scope(
                Set.copyOf(enabled),
                gate.exclusions(),
                Set.copyOf(gate.mutationComponents()),
                gate.perfScenarios(),
                gate.accessibilityPages());
        Limits limits = new Limits(
                new Coverage(gate.branchCoverageMin(), gate.mutationScoreMin()),
                new Performance(gate.responseTimeP95MaxMs(), gate.arrivalRateRps(), gate.errorRateMaxPct()),
                new Security(gate.criticalVulnerabilitiesMax(), gate.highVulnerabilitiesMax(), gate.secretsMax(),
                        gate.forbiddenLicensesMax()),
                gate.complexityMax(),
                gate.breakingChangesMax(),
                new Accessibility(gate.accessibilityViolationsMax()),
                new TestResults(gate.testSuccessRateMin(), gate.testCountMin(), gate.skippedTestsMax()));
        return new GateThresholds(scope, limits);
    }
}
