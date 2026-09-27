package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.IdentifiedFinding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M-06 循環的複雑度がしきい値を超える関数の数。
 *
 * <p>計測したコミットにある関数の件数（絶対値）で判定する。比較元との差分は取らない。
 * リリース判定で見たいのは「今のコードに複雑すぎる関数がいくつあるか」であり、
 * 既存の関数を許容したい場合は合格ラインの {@code exclusions} で外す。
 */
@Component
public class ComplexityEvaluator implements MetricEvaluator {

    @Override
    public String metricId() {
        return GateThresholds.M_COMPLEXITY;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        GateThresholds thresholds = context.thresholds();
        List<IdentifiedFinding> analyzed = context.input().findingsOf(metricId());

        List<IdentifiedFinding> exceeding = new ArrayList<>();
        List<IdentifiedFinding> warnBand = new ArrayList<>();
        for (IdentifiedFinding finding : analyzed) {
            int complexity = complexityOf(finding);
            if (complexity > thresholds.maxComplexity()) {
                exceeding.add(finding);
            } else if (complexity >= thresholds.complexityWarnFrom()) {
                warnBand.add(finding);
            }
        }

        Map<String, Object> threshold = Map.of(
                "operator", "<=", "value", 0,
                "maxComplexity", thresholds.maxComplexity());
        Map<String, Object> detail = new HashMap<>();
        detail.put("functionsInWarnBand", warnBand.size());
        detail.put("analyzedFunctions", analyzed.size());

        MeasurementStatus status = exceeding.isEmpty()
                ? (warnBand.isEmpty() ? MeasurementStatus.PASS : MeasurementStatus.WARN)
                : MeasurementStatus.FAIL;

        // 保存するのは違反と注意水準のみ。アダプタは全関数の CC 値を返すため、
        // 全件保存すると違反でない行が大量に積まれる。
        List<IdentifiedFinding> toPersist = new ArrayList<>(exceeding);
        toPersist.addAll(warnBand);

        return List.of(MetricResult.of(metricId(), null, status,
                BigDecimal.valueOf(exceeding.size()), "count", threshold,
                reasonOf(exceeding.size(), warnBand.size(), thresholds), detail, toPersist));
    }

    private static String reasonOf(int exceeding, int warnBand, GateThresholds thresholds) {
        int max = thresholds.maxComplexity();
        if (exceeding > 0) {
            return "循環的複雑度 %d 超の関数が %d 件あります".formatted(max, exceeding);
        }
        if (warnBand > 0) {
            return "循環的複雑度 %d 超の関数はありません（%d〜%d の関数が %d 件）".formatted(
                    max, thresholds.complexityWarnFrom(), max, warnBand);
        }
        return "循環的複雑度 %d 超の関数はありません".formatted(max);
    }

    private static int complexityOf(IdentifiedFinding finding) {
        return finding.finding().detail().get("complexity") instanceof Number number
                ? number.intValue()
                : 0;
    }
}
