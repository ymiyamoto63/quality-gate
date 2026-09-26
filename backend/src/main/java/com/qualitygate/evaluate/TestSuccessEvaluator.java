package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.TestTally;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * M-10 テスト成功率（docs/spec/02-metrics-spec.md M-10）。
 *
 * <p>判定の優先順位は次のとおり。上で決まったものは下を見ない。
 * <ol>
 *   <li>実行件数が最小実行件数（既定 1）未満 → ERROR（検証していないのであって、成功したのではない）</li>
 *   <li>成功率が合格ライン未満 → FAIL</li>
 *   <li>失敗したテストがある（合格ラインを 100% 未満に緩めた場合）、再実行で成功したテストがある → WARN</li>
 * </ol>
 *
 * <p>スキップは M-11 で判定するため、ここでは WARN にしない。同じスキップで 2 つの指標が
 * 黄色くなると、どちらを直せばよいかが読めない。
 *
 * <p><strong>コンポーネントごとに判定する</strong>（M-01 と同じ）。
 * 単体テストはコンポーネントに閉じており、合算すると件数の多い側が少ない側の失敗を薄める。
 */
@Component
public class TestSuccessEvaluator implements MetricEvaluator {

    private static final String UNIT = "percent";

    @Override
    public String metricId() {
        return GateThresholds.M_TEST_SUCCESS;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        GateThresholds.TestResults thresholds = context.thresholds().testResults();
        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("operator", ">=");
        threshold.put("value", thresholds.minSuccessRate());
        threshold.put("minTestCount", thresholds.minTestCount());

        List<MetricResult> results = new ArrayList<>();
        tallyByComponent(context).forEach((component, tally) -> {
            String componentName = component.isEmpty() ? null : component;
            List<IdentifiedFinding> findings = context.input().headFindingsOf(metricId()).stream()
                    .filter(f -> Objects.equals(f.finding().componentName(), componentName))
                    .toList();
            Map<String, Object> detail = tally.toDetail();
            if (tally.executed() < thresholds.minTestCount()) {
                results.add(MetricResult.of(metricId(), componentName, MeasurementStatus.ERROR,
                        null, UNIT, threshold, tooFewReason(tally, thresholds.minTestCount()),
                        detail, findings));
                return;
            }
            Judgement judgement = judge(tally, thresholds.minSuccessRate());
            results.add(MetricResult.of(metricId(), componentName, judgement.status(),
                    tally.successRate(), UNIT, threshold, judgement.reason(), detail, findings));
        });
        return results;
    }

    /**
     * コンポーネントごとの件数。テストのレポートはファイル単位で届くため、同じコンポーネントの分を合算する。
     * null（コンポーネント宣言なし）は "" に寄せる。TreeMap は null キーを持てない。
     */
    static Map<String, TestTally> tallyByComponent(EvaluationContext context) {
        Map<String, TestTally> byComponent = new TreeMap<>();
        for (RawMeasurement measurement : context.input().measurementsOf(GateThresholds.M_TEST_SUCCESS)) {
            byComponent.merge(Objects.requireNonNullElse(measurement.componentName(), ""),
                    TestTally.fromDetail(measurement.detail()), TestTally::plus);
        }
        return byComponent;
    }

    private static String tooFewReason(TestTally tally, int minimum) {
        if (tally.executed() == 0) {
            String skipped = tally.skipped() == 0 ? ""
                    : "（%d 件はすべてスキップされています）".formatted(tally.skipped());
            return "テストが 1 件も実行されていません%s。".formatted(skipped)
                    + "実行 0 件は「すべて成功」ではなく「検証していない」ため、合格にしません";
        }
        return "テストの実行件数が %d 件で、最小実行件数 %d 件に届きません"
                .formatted(tally.executed(), minimum)
                + "。送信したレポートが一部に限られていないか確認してください";
    }

    private static Judgement judge(TestTally tally, BigDecimal minimum) {
        if (!tally.meets(minimum)) {
            return new Judgement(MeasurementStatus.FAIL,
                    "テストの失敗 %d 件・エラー %d 件（実行 %d 件中）。成功率 %s%% は合格ライン %s%% 未満です"
                            .formatted(tally.failed(), tally.errored(), tally.executed(),
                                    tally.successRate().stripTrailingZeros().toPlainString(),
                                    minimum.stripTrailingZeros().toPlainString()));
        }
        long broken = tally.failed() + tally.errored();
        if (broken > 0) {
            return new Judgement(MeasurementStatus.WARN,
                    "成功率は合格ラインを満たしますが、テストが %d 件失敗しています（実行 %d 件中）"
                            .formatted(broken, tally.executed()));
        }
        if (tally.flaky() > 0) {
            return new Judgement(MeasurementStatus.WARN,
                    "%d 件は成功しましたが、うち %d 件は再実行で成功しました（不安定なテスト）"
                            .formatted(tally.executed(), tally.flaky()));
        }
        return new Judgement(MeasurementStatus.PASS,
                "テスト %d 件がすべて成功しました".formatted(tally.executed()));
    }

    private record Judgement(MeasurementStatus status, String reason) {
    }
}
