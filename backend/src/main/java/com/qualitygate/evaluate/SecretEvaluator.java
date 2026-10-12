package com.qualitygate.evaluate;

import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.Threshold;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.report.IdentifiedFinding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * M-11 シークレット検出件数（docs/metrics.md M-11）。
 *
 * <p>コミットされた鍵やトークンは、履歴に残った時点で漏えいとみなす。M-06 のように
 * 「新規だけを数える」ことはせず、<strong>検出されたものすべて</strong>を数える。
 * 既存の検出を通すと、失効させていない鍵がいつまでも残る。誤検出は、
 * {@code QG_EXCLUSIONS} でファイルごと外す。
 *
 * <p>外したファイルに検出があれば、その数を理由と内訳（{@code excludedFiles}）に残す。
 * ファイルごと外すと同じファイルの本物の鍵も数えなくなるため、合格を言葉どおりに読ませない。
 */
@Component
public class SecretEvaluator implements MetricEvaluator {

    @Override
    public String metricId() {
        return MetricCatalog.M_SECRETS;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        int max = context.thresholds().limits().security().maxSecrets();
        List<IdentifiedFinding> findings = context.input().findingsOf(metricId());

        Threshold threshold = Threshold.atMost(max);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("secrets", findings.size());
        // どの種類の鍵か（aws-access-key-id など）。値そのものは持たない（ツールが伏せ字にした一致だけ）
        detail.put("rules", new TreeSet<>(findings.stream().map(f -> f.finding().ruleId()).toList()));
        int excludedFiles = context.input().excludedFileCount(metricId());
        detail.put("excludedFiles", excludedFiles);

        MeasurementStatus status = findings.size() > max ? MeasurementStatus.FAIL : MeasurementStatus.PASS;
        String reason = findings.isEmpty()
                ? "シークレットは検出されませんでした"
                : status == MeasurementStatus.FAIL
                        ? "シークレットが %d 件検出されました（上限 %d 件）。鍵を失効させ、履歴から取り除いてください"
                                .formatted(findings.size(), max)
                        : "シークレットが %d 件検出されました（上限 %d 件以内）".formatted(findings.size(), max);
        return List.of(MetricResult.of(metricId(), null, status, BigDecimal.valueOf(findings.size()),
                "count", threshold, reason + exclusionNote(excludedFiles), detail, findings));
    }

    /**
     * 計測除外で検出の消えたファイルがあれば、理由に書き添える。誤検出を外すつもりの除外でも、
     * 同じファイルの本物の鍵まで数えなくなる。書き添えないと「検出されませんでした」が言葉どおりに読まれる。
     */
    static String exclusionNote(int excludedFiles) {
        return excludedFiles == 0 ? ""
                : "（QG_EXCLUSIONS で検査から外した %d ファイルに検出があり、件数に含めていません）".formatted(excludedFiles);
    }
}
