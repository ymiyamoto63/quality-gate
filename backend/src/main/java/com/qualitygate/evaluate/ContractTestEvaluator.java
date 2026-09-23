package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.ContractTally;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * M-08 API 契約テスト成功率（docs/02-metrics-spec.md M-08）。
 *
 * <p>判定の優先順位は次のとおり。上で決まったものは下を見ない。
 * <ol>
 *   <li>実行件数が最小実行件数（既定 1）未満 → ERROR（検証していないのであって、成功したのではない）</li>
 *   <li>成功率が合格ライン未満 → FAIL</li>
 *   <li>スキップされたテストがある、再実行で成功したテストがある → WARN</li>
 * </ol>
 *
 * <p>契約はコンポーネントの間（consumer と provider）にあるため、<strong>コンポーネントを
 * 合算して 1 つの値で判定する</strong>。片側だけ成功しても契約は守られていない。
 * コンポーネントごとの件数は内訳に残す。
 */
@Component
public class ContractTestEvaluator implements MetricEvaluator {

    private static final String UNIT = "percent";

    @Override
    public String metricId() {
        return GateThresholds.M_API_CONTRACT;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        GateThresholds thresholds = context.thresholds();
        ContractTally total = ContractTally.EMPTY;
        // null（コンポーネント宣言なし）は "" に寄せて並べる。TreeMap は null キーを持てない
        Map<String, ContractTally> byComponent = new TreeMap<>();
        for (RawMeasurement measurement : context.input().measurementsOf(metricId())) {
            ContractTally tally = ContractTally.fromDetail(measurement.detail());
            total = total.plus(tally);
            byComponent.merge(Objects.requireNonNullElse(measurement.componentName(), ""),
                    tally, ContractTally::plus);
        }
        List<IdentifiedFinding> findings = context.input().headFindingsOf(metricId());

        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("operator", ">=");
        threshold.put("value", thresholds.contractMinSuccessRate());
        threshold.put("minTestCount", thresholds.contractMinTestCount());

        Map<String, Object> detail = new LinkedHashMap<>(total.toDetail());
        if (byComponent.size() > 1 || !byComponent.containsKey("")) {
            Map<String, Object> components = new LinkedHashMap<>();
            byComponent.forEach((name, tally) ->
                    components.put(name.isEmpty() ? "(未指定)" : name, tally.toDetail()));
            detail.put("components", components);
        }

        if (total.executed() < thresholds.contractMinTestCount()) {
            return List.of(MetricResult.of(metricId(), null, MeasurementStatus.ERROR, null, UNIT,
                    threshold, tooFewReason(total, thresholds.contractMinTestCount()), detail,
                    findings));
        }

        Judgement judgement = judge(total, thresholds.contractMinSuccessRate());
        return List.of(MetricResult.of(metricId(), null, judgement.status(),
                total.successRate(), UNIT, threshold, judgement.reason(), detail, findings));
    }

    private static String tooFewReason(ContractTally total, int minimum) {
        if (total.executed() == 0) {
            String skipped = total.skipped() == 0 ? ""
                    : "（%d 件はすべてスキップされています）".formatted(total.skipped());
            return "契約テストが 1 件も実行されていません%s。".formatted(skipped)
                    + "実行 0 件は「すべて成功」ではなく「検証していない」ため、合格にしません";
        }
        return "契約テストの実行件数が %d 件で、最小実行件数 %d 件に届きません"
                .formatted(total.executed(), minimum)
                + "。送信したレポートが一部に限られていないか確認してください";
    }

    private static Judgement judge(ContractTally total, BigDecimal minimum) {
        if (!total.meets(minimum)) {
            return new Judgement(MeasurementStatus.FAIL,
                    "契約テストの失敗 %d 件・エラー %d 件（実行 %d 件中）。成功率 %s%% は合格ライン %s%% 未満です"
                            .formatted(total.failed(), total.errored(), total.executed(),
                                    total.successRate().stripTrailingZeros().toPlainString(),
                                    minimum.stripTrailingZeros().toPlainString()));
        }
        long broken = total.failed() + total.errored();
        if (broken > 0) {
            // 合格ラインを 100% 未満に緩めた場合に限り、ここに来る
            return new Judgement(MeasurementStatus.WARN,
                    "成功率は合格ラインを満たしますが、契約テストが %d 件失敗しています（実行 %d 件中）"
                            .formatted(broken, total.executed()));
        }
        if (total.skipped() > 0) {
            return new Judgement(MeasurementStatus.WARN,
                    "実行した %d 件は成功しましたが、%d 件がスキップされています。スキップした契約は検証されていません"
                            .formatted(total.executed(), total.skipped()));
        }
        if (total.flaky() > 0) {
            return new Judgement(MeasurementStatus.WARN,
                    "%d 件は成功しましたが、うち %d 件は再実行で成功しました（不安定なテスト）"
                            .formatted(total.executed(), total.flaky()));
        }
        return new Judgement(MeasurementStatus.PASS,
                "契約テスト %d 件がすべて成功しました".formatted(total.executed()));
    }

    private record Judgement(MeasurementStatus status, String reason) {
    }
}
