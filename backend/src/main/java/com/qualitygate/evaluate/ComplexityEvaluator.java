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
 * M-07 循環的複雑度 15 超の新規関数数。
 *
 * <p>既存のレガシーコードを一括で不合格にしないため、対象を次の 2 種に限定する。
 * <ul>
 *   <li>(a) ベースに存在せず、新たに追加された関数で CC &gt; 15</li>
 *   <li>(b) ベースにも存在するが、本変更で CC が増加し、その結果 15 を超えたもの</li>
 * </ul>
 *
 * <p>ベース時点ですでに CC &gt; 15 で、本変更で悪化していない関数は対象外とする。
 * 変更していても複雑度を悪化させていなければ通す。
 *
 * <p><strong>ベース側のデータが無い場合は新規関数数を 0 とする。</strong>
 * 何が新規かを判定できない状態で既存の複雑度超過を「新規」に計上すると、
 * その変更が大量の問題を持ち込んだという誤った表示になる。
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
        Map<String, Integer> head = complexityByFingerprint(
                context.input().headFindingsOf(metricId()));
        Map<String, Integer> base = complexityByFingerprint(
                context.input().baseFindingsOf(metricId()));

        List<IdentifiedFinding> exceeding = new ArrayList<>();
        List<IdentifiedFinding> warnBand = new ArrayList<>();
        int newlyExceeding = 0;

        for (IdentifiedFinding finding : context.input().headFindingsOf(metricId())) {
            int complexity = head.getOrDefault(finding.fingerprint(), 0);
            if (complexity > thresholds.maxComplexity()) {
                exceeding.add(finding);
                if (!base.isEmpty() && isNewlyExceeding(finding.fingerprint(), complexity, base)) {
                    newlyExceeding++;
                }
            } else if (complexity >= thresholds.complexityWarnFrom()) {
                warnBand.add(finding);
            }
        }

        Map<String, Object> threshold = Map.of(
                "operator", "<=", "value", 0,
                "maxComplexity", thresholds.maxComplexity());
        Map<String, Object> detail = new HashMap<>();
        detail.put("functionsOverThreshold", exceeding.size());
        detail.put("functionsInWarnBand", warnBand.size());
        detail.put("analyzedFunctions", head.size());
        detail.put("baseComparisonAvailable", !base.isEmpty());

        MeasurementStatus status = statusOf(newlyExceeding, warnBand.size());
        String reason = reasonOf(base.isEmpty(), newlyExceeding, exceeding.size(),
                warnBand.size(), thresholds);

        // 保存するのは違反と注意水準のみ。アダプタは全関数の CC 値を返すため、
        // 全件保存すると違反でない行が大量に積まれる。
        List<IdentifiedFinding> toPersist = new ArrayList<>(exceeding);
        toPersist.addAll(warnBand);

        return List.of(MetricResult.of(metricId(), null, status,
                BigDecimal.valueOf(newlyExceeding), "count", threshold, reason, detail, toPersist));
    }

    /** ベースに存在しない（新規追加）、またはベースより CC が増加している。 */
    private static boolean isNewlyExceeding(String fingerprint, int complexity,
                                            Map<String, Integer> base) {
        Integer baseComplexity = base.get(fingerprint);
        return baseComplexity == null || complexity > baseComplexity;
    }

    private static MeasurementStatus statusOf(int newlyExceeding, int warnBand) {
        if (newlyExceeding > 0) {
            return MeasurementStatus.FAIL;
        }
        return warnBand > 0 ? MeasurementStatus.WARN : MeasurementStatus.PASS;
    }

    private static String reasonOf(boolean noBase, int newlyExceeding, int exceeding,
                                   int warnBand, GateThresholds thresholds) {
        int max = thresholds.maxComplexity();
        if (noBase) {
            return exceeding == 0
                    ? "循環的複雑度 %d 超の関数はありません".formatted(max)
                    : ("ベース比較ができないため、既存の複雑度超過 %d 件は新規として計上していません"
                            + "（比較には base スコープの解析結果が必要です）").formatted(exceeding);
        }
        if (newlyExceeding > 0) {
            return "複雑度 %d 超の新規・悪化した関数が %d 件あります".formatted(max, newlyExceeding);
        }
        if (warnBand > 0) {
            return "複雑度 %d 超の新規関数はありません（%d〜%d の関数が %d 件）".formatted(
                    max, thresholds.complexityWarnFrom(), max, warnBand);
        }
        return "複雑度 %d 超の新規関数はありません".formatted(max);
    }

    private static Map<String, Integer> complexityByFingerprint(
            List<IdentifiedFinding> findings) {
        Map<String, Integer> result = new HashMap<>();
        for (IdentifiedFinding finding : findings) {
            Object complexity = finding.finding().detail().get("complexity");
            if (complexity instanceof Number number) {
                result.put(finding.fingerprint(), number.intValue());
            }
        }
        return result;
    }
}
