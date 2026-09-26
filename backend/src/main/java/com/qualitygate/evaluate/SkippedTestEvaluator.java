package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.TestTally;
import com.qualitygate.domain.report.IdentifiedFinding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * M-11 スキップされたテスト数（docs/spec/02-metrics-spec.md M-11）。
 *
 * <p>スキップ（{@code @Disabled} / {@code it.skip} など）は失敗にならないため、成功率（M-10）では
 * 見えない。落ちるテストを黙らせる手段として使われると、成功率 100% のまま検証が減っていく。
 * そこで<strong>比較対象 Run からの増加</strong>を判定する。
 * 既存のスキップを一括で不合格にしないのは M-07 と同じ考え方による。
 *
 * <p>判定の優先順位は次のとおり。
 * <ol>
 *   <li>件数の上限（{@code max_skipped}）を超えた → FAIL</li>
 *   <li>比較対象 Run からの増加が上限（{@code max_skipped_increase}、既定 0）を超えた → FAIL</li>
 *   <li>それ以外 → PASS（比較対象が無ければ増加は判定せず、件数だけを記録する）</li>
 * </ol>
 *
 * <p>件数はコンポーネントごとに数える（M-10 と同じ）。
 */
@Component
public class SkippedTestEvaluator implements MetricEvaluator {

    private static final String UNIT = "count";

    @Override
    public String metricId() {
        return GateThresholds.M_SKIPPED_TESTS;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        GateThresholds.TestResults thresholds = context.thresholds().testResults();
        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("operator", "<=");
        threshold.put("value", thresholds.maxSkipped());
        threshold.put("maxIncrease", thresholds.maxSkippedIncrease());

        List<MetricResult> results = new ArrayList<>();
        TestSuccessEvaluator.tallyByComponent(context).forEach((component, tally) -> {
            String componentName = component.isEmpty() ? null : component;
            List<IdentifiedFinding> findings = context.input().headFindingsOf(metricId()).stream()
                    .filter(f -> Objects.equals(f.finding().componentName(), componentName))
                    .toList();
            Optional<BigDecimal> previous = context.previousValue(metricId(), componentName);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("skipped", tally.skipped());
            detail.put("executed", tally.executed());
            previous.ifPresent(value -> detail.put("increase", tally.skipped() - value.longValue()));

            Judgement judgement = judge(tally, previous, thresholds);
            results.add(MetricResult.of(metricId(), componentName, judgement.status(),
                    BigDecimal.valueOf(tally.skipped()), UNIT, threshold, judgement.reason(),
                    detail, findings));
        });
        return results;
    }

    private static Judgement judge(TestTally tally, Optional<BigDecimal> previous,
                                   GateThresholds.TestResults thresholds) {
        long skipped = tally.skipped();
        if (thresholds.maxSkipped() != null && skipped > thresholds.maxSkipped()) {
            return new Judgement(MeasurementStatus.FAIL,
                    "スキップされたテストが %d 件あり、上限 %d 件を超えています"
                            .formatted(skipped, thresholds.maxSkipped()));
        }
        if (previous.isEmpty()) {
            return new Judgement(MeasurementStatus.PASS,
                    "スキップされたテストは %d 件です（比較対象の Run が無いため、増加は判定していません）"
                            .formatted(skipped));
        }
        long increase = skipped - previous.get().longValue();
        if (increase > thresholds.maxSkippedIncrease()) {
            return new Judgement(MeasurementStatus.FAIL,
                    "スキップされたテストが前回から %d 件増えました（%d 件 → %d 件、増加の上限 %d 件）。"
                            .formatted(increase, previous.get().longValue(), skipped,
                                    thresholds.maxSkippedIncrease())
                            + "スキップしたテストは検証されていません");
        }
        if (skipped == 0) {
            return new Judgement(MeasurementStatus.PASS, "スキップされたテストはありません");
        }
        return new Judgement(MeasurementStatus.PASS,
                "スキップされたテストは %d 件です（前回 %d 件）"
                        .formatted(skipped, previous.get().longValue()));
    }

    private record Judgement(MeasurementStatus status, String reason) {
    }
}
