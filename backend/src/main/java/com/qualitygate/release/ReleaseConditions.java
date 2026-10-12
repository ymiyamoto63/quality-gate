package com.qualitygate.release;

import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.model.JudgementScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * リリース判定の「判定の条件」。判定したときに Run に焼き付けた設定（DD-28）のうち、判定の範囲を狭めていたものを、
 * 開発に携わっていない方にも読める文に直す。
 *
 * <p>出すのは範囲を<strong>狭める</strong>設定だけ。シナリオや画面を指定して確かめる範囲を広げた設定は、
 * 結論を良く見せる方向に働かないため出さない。判定から外した指標は「判定の対象外」に出すため、ここでは扱わない。
 */
final class ReleaseConditions {

    /** 計測除外（{@code QG_EXCLUSIONS}）が効く指標（docs/metrics.md 2.3）。 */
    static final List<String> EXCLUSION_METRICS = List.of(MetricCatalog.M_BRANCH_COVERAGE, MetricCatalog.M_MUTATION,
            MetricCatalog.M_VULNERABILITIES, MetricCatalog.M_COMPLEXITY, MetricCatalog.M_SECRETS,
            MetricCatalog.M_LICENSES);

    /** 指定が無いことを表す値。 */
    static final String UNSPECIFIED = "指定なし";

    private ReleaseConditions() {
    }

    /**
     * 判定の条件。範囲を狭める設定が無ければ空の一覧。判定時の設定を記録する前（V004 より前）に判定した Run では空
     * （{@link Optional#empty()}。分からない）。
     */
    static Optional<List<ReleaseReportResponse.ReleaseCondition>> of(Run run) {
        return run.getScope().map(ReleaseConditions::of);
    }

    static List<ReleaseReportResponse.ReleaseCondition> of(JudgementScope scope) {
        List<ReleaseReportResponse.ReleaseCondition> conditions = new ArrayList<>();
        List<String> affected = EXCLUSION_METRICS.stream()
                .filter(metricId -> !scope.disabledMetrics().contains(metricId))
                .map(metricId -> MetricCatalog.of(metricId).name())
                .toList();
        if (!scope.exclusions().isEmpty() && !affected.isEmpty()) {
            conditions.add(new ReleaseReportResponse.ReleaseCondition("exclusions", "計測から外したファイル",
                    String.join(", ", scope.exclusions()),
                    "このパターンに一致するファイルは、%s の計測に含めていません。外したファイルの数は、指標ごとの行に出ています"
                            .formatted(String.join("・", affected)),
                    "QG_EXCLUSIONS"));
        }
        if (enabled(scope, MetricCatalog.M_MUTATION) && !scope.mutationComponents().isEmpty()) {
            conditions.add(new ReleaseReportResponse.ReleaseCondition("mutationComponents",
                    MetricCatalog.of(MetricCatalog.M_MUTATION).name() + "の対象コンポーネント",
                    String.join(", ", scope.mutationComponents()),
                    "ここに挙げたコンポーネントだけを判定しています。ほかのコンポーネントは判定の対象外です",
                    "QG_MUTATION_COMPONENTS"));
        }
        if (enabled(scope, MetricCatalog.M_PERFORMANCE_P95) && scope.perfScenarios().isEmpty()) {
            conditions.add(new ReleaseReportResponse.ReleaseCondition("perfScenarios",
                    MetricCatalog.of(MetricCatalog.M_PERFORMANCE_P95).name() + " のシナリオ", UNSPECIFIED,
                    "シナリオ（計測する操作）を限定せずに判定しています。負荷試験から必要な操作が抜けていても、不合格にはなりません",
                    "QG_PERF_SCENARIOS"));
        }
        if (enabled(scope, MetricCatalog.M_ACCESSIBILITY) && scope.accessibilityPages().isEmpty()) {
            conditions.add(new ReleaseReportResponse.ReleaseCondition("accessibilityPages",
                    "アクセシビリティを検査する画面", UNSPECIFIED,
                    "画面を限定せずに判定しています。1 画面以上を検査したことだけを確かめ、検査すべき画面が抜けていても不合格にはなりません",
                    "QG_ACCESSIBILITY_PAGES"));
        }
        return conditions;
    }

    private static boolean enabled(JudgementScope scope, String metricId) {
        return !scope.disabledMetrics().contains(metricId);
    }
}
