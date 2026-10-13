package com.qualitygate.evaluate;

import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.Threshold;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M-06 循環的複雑度がしきい値を超える関数の数。
 *
 * <p>計測したコミットにある関数の件数（絶対値）で判定する。比較元との差分は取らない。
 * リリース判定で見たいのは「今のコードに複雑すぎる関数がいくつあるか」であり、
 * 既存の関数を許容したい場合は {@code QG_EXCLUSIONS} で外す。
 *
 * <p>コンポーネントごとに判定する（backend は PMD、frontend は ESLint）。合算すると、
 * 片方の解析が失敗して成果物が届かなかったときに、残りだけで合格になってしまう。
 * 届いた成果物はアダプタが件数の行（{@code analyzedFunctions}）として返すため、
 * 関数が 1 つも無いコンポーネントも「解析した」と分かる。届かなかったコンポーネントは
 * {@link RunEvaluationService} が ERROR にする（DD-26）。
 */
@Component
public class ComplexityEvaluator implements MetricEvaluator {

    @Override
    public String metricId() {
        return MetricCatalog.M_COMPLEXITY;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        // キーはコンポーネント名。コンポーネントを付けずに届いた成果物は "" にまとめる（TreeMap は null キーを持てない）
        Map<String, List<IdentifiedFinding>> byComponent = new TreeMap<>();
        Map<String, Integer> excludedFiles = new HashMap<>();
        context.input().measurementsOf(metricId()).forEach(measurement -> {
            byComponent.computeIfAbsent(keyOf(measurement.componentName()), k -> new ArrayList<>());
            // 計測除外で読み捨てたファイルの数（アダプタが件数の行の内訳に載せる）。同じコンポーネントの成果物が複数あれば足す
            excludedFiles.merge(keyOf(measurement.componentName()), excludedFilesOf(measurement), Integer::sum);
        });
        context.input().findingsOf(metricId()).forEach(finding ->
                byComponent.computeIfAbsent(keyOf(finding.finding().componentName()), k -> new ArrayList<>())
                        .add(finding));

        List<MetricResult> results = new ArrayList<>();
        byComponent.forEach((component, analyzed) ->
                results.add(evaluateComponent(component.isEmpty() ? null : component, analyzed,
                        excludedFiles.getOrDefault(component, 0), context)));
        return results;
    }

    private MetricResult evaluateComponent(String componentName, List<IdentifiedFinding> analyzed,
                                           int excludedFiles, EvaluationContext context) {
        int max = context.thresholds().limits().maxComplexity();
        List<IdentifiedFinding> exceeding = analyzed.stream()
                .filter(finding -> complexityOf(finding) > max)
                .toList();

        Threshold threshold = Threshold.atMost(0)
                .withLimit("maxComplexity", Threshold.AT_MOST, "関数ごとの循環的複雑度の上限", max);
        Map<String, Object> detail = new HashMap<>();
        detail.put("analyzedFunctions", analyzed.size());
        detail.put("excludedFiles", excludedFiles);

        MeasurementStatus status = exceeding.isEmpty() ? MeasurementStatus.PASS : MeasurementStatus.FAIL;
        String reason = exceeding.isEmpty()
                ? "循環的複雑度 %d 超の関数はありません".formatted(max)
                : "循環的複雑度 %d 超の関数が %d 件あります".formatted(max, exceeding.size());

        // 保存するのは違反だけ。アダプタは全関数の CC 値を返すため、
        // 全件保存すると違反でない行が大量に積まれる。
        return MetricResult.of(metricId(), componentName, status,
                BigDecimal.valueOf(exceeding.size()), "count", threshold, reason, detail, exceeding);
    }

    private static String keyOf(String componentName) {
        return componentName == null ? "" : componentName;
    }

    private static int excludedFilesOf(RawMeasurement measurement) {
        return measurement.detail().get("excludedFiles") instanceof Number number ? number.intValue() : 0;
    }

    private static int complexityOf(IdentifiedFinding finding) {
        return finding.finding().detail().get("complexity") instanceof Number number
                ? number.intValue()
                : 0;
    }
}
