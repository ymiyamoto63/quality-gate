package com.qualitygate.evaluate;

import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.Threshold;
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
 * M-10 スキップされたテスト数（docs/metrics.md M-10）。
 *
 * <p>スキップ（{@code @Disabled} / {@code it.skip} など）は失敗にならないため、成功率（M-09）では
 * 見えない。落ちるテストを黙らせる手段として使われると、成功率 100% のまま検証が減っていく。
 * そこで<strong>件数そのもの</strong>を上限（{@code QG_SKIPPED_TESTS_MAX}、既定 0）で判定する。
 * 前回からの増加で判定すると、比較対象の Run が無いときは何件でも合格になり、同じコミットの判定が
 * それまでに何を計測したかで変わってしまう（DD-25）。既存のスキップを認めるときは、上限を今の件数に
 * 合わせる（M-06 を QG_EXCLUSIONS で除くのと同じく、運用者が明示的に決める）。
 *
 * <p>判定の優先順位は次のとおり。
 * <ol>
 *   <li>件数が上限を超えた → FAIL</li>
 *   <li>それ以外 → PASS</li>
 * </ol>
 * 比較対象 Run からの増加は、理由と内訳（{@code increase}）に書き添えるだけで合否には使わない。
 *
 * <p>件数はコンポーネントごとに数える（M-09 と同じ）。
 */
@Component
public class SkippedTestEvaluator implements MetricEvaluator {

    private static final String UNIT = "count";

    @Override
    public String metricId() {
        return MetricCatalog.M_SKIPPED_TESTS;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        int max = context.thresholds().testResults().maxSkipped();
        Threshold threshold = Threshold.atMost(max);

        List<MetricResult> results = new ArrayList<>();
        TestSuccessEvaluator.tallyByComponent(context).forEach((component, tally) -> {
            String componentName = component.isEmpty() ? null : component;
            List<IdentifiedFinding> findings = context.input().findingsOf(metricId()).stream()
                    .filter(f -> Objects.equals(f.finding().componentName(), componentName))
                    .toList();
            Optional<BigDecimal> previous = context.previousValue(metricId(), componentName);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("skipped", tally.skipped());
            detail.put("executed", tally.executed());
            previous.ifPresent(value -> detail.put("increase", tally.skipped() - value.longValue()));

            Judgement judgement = judge(tally, previous, max);
            results.add(MetricResult.of(metricId(), componentName, judgement.status(),
                    BigDecimal.valueOf(tally.skipped()), UNIT, threshold, judgement.reason(),
                    detail, findings));
        });
        return results;
    }

    private static Judgement judge(TestTally tally, Optional<BigDecimal> previous, int max) {
        long skipped = tally.skipped();
        String note = increaseNote(skipped, previous);
        if (skipped > max) {
            return new Judgement(MeasurementStatus.FAIL,
                    "スキップされたテストが %d 件あります（上限 %d 件）。スキップしたテストは検証されていません"
                            .formatted(skipped, max) + note);
        }
        if (skipped == 0) {
            return new Judgement(MeasurementStatus.PASS, "スキップされたテストはありません" + note);
        }
        return new Judgement(MeasurementStatus.PASS,
                "スキップされたテストは %d 件です（上限 %d 件）".formatted(skipped, max) + note);
    }

    /** 前回より増えていれば、合否とは別に書き添える（上限の内側で増えていることに気づけるように）。 */
    private static String increaseNote(long skipped, Optional<BigDecimal> previous) {
        if (previous.isEmpty()) {
            return "";
        }
        long before = previous.get().longValue();
        if (skipped <= before) {
            return "";
        }
        return "。前回より %d 件増えています（%d 件 → %d 件）".formatted(skipped - before, before, skipped);
    }

    private record Judgement(MeasurementStatus status, String reason) {
    }
}
