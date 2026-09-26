package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.MutationScope;
import com.qualitygate.domain.report.MutationTally;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * M-02 ミューテーションスコア（docs/spec/02-metrics-spec.md M-02）。
 *
 * <p>判定の優先順位は次のとおり。上で決まったものは下を見ない。
 * <ol>
 *   <li>ミューテーションが 0 個 → 値なしの合格（ロジックを含まない変更）</li>
 *   <li>生成・実行に失敗したものが全体の 10% 超 → ERROR（値そのものが疑わしい）</li>
 *   <li>しきい値未満 → FAIL</li>
 *   <li>注意水準未満、TIMED_OUT が 10% 超、前回比 2 ポイント以上の低下 → WARN</li>
 * </ol>
 *
 * <p><strong>コンポーネントを合算しない</strong>のは M-01 と同じ理由による。
 * 同じコンポーネントに複数の成果物（Maven のマルチモジュールなど）があれば、
 * 件数で合算してから計算する。
 */
@Component
public class MutationScoreEvaluator implements MetricEvaluator {

    /** 注意水準はしきい値にこの幅を足したところ（60% なら 65%）。 */
    static final BigDecimal WARN_MARGIN = new BigDecimal("5");
    /** 前回からこれ以上下がったら注意を出す（ポイント）。 */
    static final BigDecimal DROP_WARN_POINTS = new BigDecimal("2");
    /** 生成・実行に失敗したものがこの割合（%）を超えたら、計測が機能していないとみなす。 */
    static final BigDecimal FAILED_RATIO_LIMIT = new BigDecimal("10");
    /** TIMED_OUT がこの割合（%）を超えたら、遅いランナーによる過大評価を疑う。 */
    static final BigDecimal TIMEOUT_RATIO_LIMIT = new BigDecimal("10");

    private static final String UNIT = "percent";

    @Override
    public String metricId() {
        return GateThresholds.M_MUTATION;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        GateThresholds thresholds = context.thresholds();
        Set<String> targets = thresholds.mutationComponents();

        // null（コンポーネント宣言なし）は "" に寄せて並べる。TreeMap は null キーを持てない
        Map<String, List<RawMeasurement>> byComponent = new TreeMap<>();
        for (RawMeasurement measurement : context.input().measurementsOf(metricId())) {
            byComponent.computeIfAbsent(Objects.requireNonNullElse(
                    measurement.componentName(), ""), k -> new ArrayList<>()).add(measurement);
        }

        List<MetricResult> results = new ArrayList<>();
        byComponent.forEach((component, measurements) -> {
            String name = component.isEmpty() ? null : component;
            if (name != null && !targets.isEmpty() && !targets.contains(name)) {
                // 対象外のコンポーネントから届いた値は判定に使わない。設定で対象を
                // 絞っている以上、そこから外れた値で合否を左右させない
                results.add(MetricResult.notApplicable(metricId(), name,
                        "設定で対象としていないコンポーネントです（対象: %s）。値は判定に用いません"
                                .formatted(String.join(", ", new TreeSet<>(targets)))));
            } else {
                results.add(evaluateComponent(name, measurements, context, thresholds));
            }
        });

        results.addAll(missingTargets(byComponent.keySet(), targets));
        results.addAll(notApplicableComponents(context, byComponent.keySet(), targets));
        results.sort(Comparator.comparing(r -> Objects.requireNonNullElse(r.componentName(), "")));
        return results;
    }

    /**
     * 対象コンポーネントの成果物が無ければ ERROR とする（fail-closed）。
     *
     * <p>コンポーネント宣言の無い成果物が 1 つでもあれば確認しない。どのコンポーネントの
     * 値かを知る手がかりが無く、それを全体の値とみなすしかないためである。
     */
    private List<MetricResult> missingTargets(Set<String> submitted, Set<String> targets) {
        if (submitted.contains("")) {
            return List.of();
        }
        List<MetricResult> missing = new ArrayList<>();
        for (String target : new TreeSet<>(targets)) {
            if (!submitted.contains(target)) {
                missing.add(new MetricResult(metricId(), target, MeasurementStatus.ERROR, null,
                        null, Map.of(), "%s の成果物（mutations.xml）が提出されていません"
                                .formatted(target), Map.of(), List.of(), null));
            }
        }
        return missing;
    }

    /**
     * この Run で他の指標を計測しているのに M-02 の対象でないコンポーネントを
     * 「対象外」として並べる。
     *
     * <p>黙って行を出さないと、frontend の M-02 が「測り忘れ」なのか
     * 「測りようがない」のか画面から区別できない（docs/spec/02-metrics-spec.md M-02）。
     * リポジトリの構成を別に持たず、この Run に現れたコンポーネントを使うのは、
     * 実際に計測しているものだけを並べるため。
     */
    private List<MetricResult> notApplicableComponents(EvaluationContext context,
                                                       Set<String> submitted,
                                                       Set<String> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new TreeSet<>();
        for (RawMeasurement measurement : context.input().measurements()) {
            if (measurement.componentName() != null) {
                seen.add(measurement.componentName());
            }
        }
        List<MetricResult> results = new ArrayList<>();
        for (String component : seen) {
            if (!targets.contains(component) && !submitted.contains(component)) {
                results.add(MetricResult.notApplicable(metricId(), component,
                        "PIT は JVM 言語専用のため、このコンポーネントは計測の対象外です"
                                + "（テストの実効性はブランチカバレッジで担保します）"));
            }
        }
        return results;
    }

    private MetricResult evaluateComponent(String component, List<RawMeasurement> measurements,
                                           EvaluationContext context,
                                           GateThresholds thresholds) {
        Set<String> scopes = new TreeSet<>();
        measurements.forEach(m -> scopes.add(Objects.requireNonNullElse(m.variant(), "")));
        if (scopes.size() > 1) {
            // 変更範囲と全量を件数で足すと、どちらでもない値になる
            return new MetricResult(metricId(), component, MeasurementStatus.ERROR, null, null,
                    Map.of(), "実行範囲の異なる成果物が混在しています（%s）。同じ Run では範囲を揃えてください"
                            .formatted(String.join(" / ", scopes)), Map.of(), List.of(), null);
        }
        String scope = measurements.getFirst().variant();

        MutationTally tally = MutationTally.EMPTY;
        long excludedFiles = 0;
        for (RawMeasurement measurement : measurements) {
            tally = tally.plus(MutationTally.fromDetail(measurement.detail()));
            if (measurement.detail().get("excludedFiles") instanceof Number number) {
                excludedFiles += number.longValue();
            }
        }

        Map<String, Object> threshold = Map.of(
                "operator", ">=", "value", thresholds.mutationThreshold());
        Map<String, Object> detail = new LinkedHashMap<>(tally.toDetail());
        detail.put("excludedFiles", excludedFiles);
        detail.put("reports", measurements.size());
        if (scope != null) {
            detail.put(MutationScope.METADATA_KEY, scope);
        }

        Judgement judgement = judge(tally, component, scope, context, thresholds);
        BigDecimal value = judgement.status() == MeasurementStatus.ERROR
                ? null : scaled(tally.score());
        return new MetricResult(metricId(), component, judgement.status(), value, UNIT,
                threshold, judgement.reason(), detail, List.of(), scope);
    }

    private Judgement judge(MutationTally tally, String component, String scope,
                            EvaluationContext context, GateThresholds thresholds) {
        if (tally.total() == 0) {
            return new Judgement(MeasurementStatus.PASS,
                    "ミューテーションが生成されませんでした（変更にロジックが含まれていない可能性があります）");
        }

        BigDecimal failedRatio = tally.ratioOf(tally.failed());
        if (failedRatio.compareTo(FAILED_RATIO_LIMIT) > 0) {
            // 値を出さないのは、生き残ったはずの mutation が失敗側に紛れている可能性があり、
            // 数字を信じる根拠が無いため
            return new Judgement(MeasurementStatus.ERROR,
                    "生成・実行に失敗したミューテーションが全体の %s%% あり、上限 %s%% を超えています"
                            .formatted(failedRatio.toPlainString(),
                                    FAILED_RATIO_LIMIT.toPlainString())
                            + "（NON_VIABLE %d / RUN_ERROR %d / MEMORY_ERROR %d / 未完了 %d）。PIT の設定を確認してください"
                            .formatted(tally.nonViable(), tally.runError(), tally.memoryError(),
                                    tally.notCompleted()));
        }

        BigDecimal value = scaled(tally.score());
        String limit = thresholds.mutationThreshold().toPlainString();
        if (value.compareTo(thresholds.mutationThreshold()) < 0) {
            return new Judgement(MeasurementStatus.FAIL,
                    "しきい値 %s%% を下回っています（実測 %s%%、検出 %d / 対象 %d）".formatted(
                            limit, value.toPlainString(),
                            tally.killed() + tally.timedOut(), tally.scored()));
        }

        BigDecimal warnBelow = thresholds.mutationThreshold().add(WARN_MARGIN);
        if (value.compareTo(warnBelow) < 0) {
            return new Judgement(MeasurementStatus.WARN,
                    "しきい値 %s%% は満たしていますが、注意水準 %s%% を下回っています（実測 %s%%）"
                            .formatted(limit, warnBelow.toPlainString(), value.toPlainString()));
        }

        BigDecimal timeoutRatio = tally.ratioOf(tally.timedOut());
        if (timeoutRatio.compareTo(TIMEOUT_RATIO_LIMIT) > 0) {
            // TIMED_OUT は検出側に数えるため、遅いランナーほどスコアが高く出る
            return new Judgement(MeasurementStatus.WARN,
                    "TIMED_OUT が全体の %s%% あり、実態より高く出ている可能性があります（実測 %s%%）。ランナーの性能を確認してください"
                            .formatted(timeoutRatio.toPlainString(), value.toPlainString()));
        }

        BigDecimal previous = context.previousValue(metricId(), component, scope).orElse(null);
        if (previous != null && previous.subtract(value).compareTo(DROP_WARN_POINTS) >= 0) {
            return new Judgement(MeasurementStatus.WARN,
                    "前回より %s ポイント低下しています（%s%% → %s%%）".formatted(
                            scaled(previous.subtract(value)).toPlainString(),
                            scaled(previous).toPlainString(), value.toPlainString()));
        }

        return new Judgement(MeasurementStatus.PASS,
                "しきい値 %s%% を満たしています（実測 %s%%、検出 %d / 対象 %d）".formatted(
                        limit, value.toPlainString(),
                        tally.killed() + tally.timedOut(), tally.scored()));
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record Judgement(MeasurementStatus status, String reason) {
    }
}
