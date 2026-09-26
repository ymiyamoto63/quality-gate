package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.IdentifiedFinding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * M-13 シークレット検出件数（docs/initial/02-metrics-spec.md M-13）。
 *
 * <p>コミットされた鍵やトークンは、履歴に残った時点で漏えいとみなす。M-07 のように
 * 「新規だけを数える」ことはせず、<strong>検出されたものすべて</strong>を数える。
 * 既存の検出を通すと、失効させていない鍵がいつまでも残る。誤検出や失効済みの鍵は、
 * 違反単位の免除（理由と期限つき）で外す。
 */
@Component
public class SecretEvaluator implements MetricEvaluator {

    @Override
    public String metricId() {
        return GateThresholds.M_SECRETS;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        int max = context.thresholds().maxSecrets();
        // 免除された検出は RunEvaluationService が入力から除いてから渡す（ActiveWaivers）
        List<IdentifiedFinding> findings = context.input().headFindingsOf(metricId());

        Map<String, Object> threshold = Map.of("operator", "<=", "value", max);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("secrets", findings.size());
        // どの種類の鍵か（aws-access-key-id など）。値そのものは持たない（ツールが伏せ字にした一致だけ）
        detail.put("rules", new TreeSet<>(findings.stream().map(f -> f.finding().ruleId()).toList()));

        MeasurementStatus status = findings.size() > max ? MeasurementStatus.FAIL : MeasurementStatus.PASS;
        String reason = findings.isEmpty()
                ? "シークレットは検出されませんでした"
                : status == MeasurementStatus.FAIL
                        ? "シークレットが %d 件検出されました（上限 %d 件）。鍵を失効させ、履歴から取り除いてください"
                                .formatted(findings.size(), max)
                        : "シークレットが %d 件検出されました（上限 %d 件以内）".formatted(findings.size(), max);
        return List.of(MetricResult.of(metricId(), null, status, BigDecimal.valueOf(findings.size()),
                "count", threshold, reason, detail, findings));
    }
}
