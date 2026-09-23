package com.qualitygate.evaluate;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M-09 OpenAPI の破壊的変更件数（docs/02-metrics-spec.md M-09）。
 *
 * <p>判定の優先順位は次のとおり。上で決まったものは下を見ない。
 * <ol>
 *   <li>比較元に OpenAPI 定義が無く、変更も報告されていない → 対象外（新規 API）</li>
 *   <li>破壊的変更（oasdiff の level 3）が合格ラインを超える → FAIL</li>
 *   <li>破壊的になりうる変更（level 2）がある → WARN</li>
 * </ol>
 *
 * <p>件数は違反（変更）から数える。複数の成果物に同じ変更が載っていても、
 * fingerprint で名寄せ済みのため 1 件になる。
 */
@Component
public class BreakingChangeEvaluator implements MetricEvaluator {

    private static final String UNIT = "count";

    @Override
    public String metricId() {
        return GateThresholds.M_BREAKING_CHANGES;
    }

    @Override
    public List<MetricResult> evaluate(EvaluationContext context) {
        int maximum = context.thresholds().maxBreakingChanges();
        List<RawMeasurement> measurements = context.input().measurementsOf(metricId());
        List<IdentifiedFinding> findings = context.input().headFindingsOf(metricId());

        long breaking = count(findings, Severity.HIGH);
        long warnings = count(findings, Severity.MEDIUM);
        long informational = findings.size() - breaking - warnings;

        boolean baseMissing = !measurements.isEmpty() && measurements.stream().allMatch(m ->
                Boolean.TRUE.equals(m.detail().get(ParseContext.BASE_SPEC_MISSING)));
        if (baseMissing && breaking + warnings == 0) {
            return List.of(MetricResult.notApplicable(metricId(), null,
                    "比較元のコミットに OpenAPI 定義が無いため、破壊的変更は数えられません（新規 API）"));
        }

        Map<String, Object> threshold = Map.of("operator", "<=", "value", maximum);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("breaking", breaking);
        detail.put("warnings", warnings);
        detail.put("informational", informational);

        // 非破壊的な変更（changelog の出力を送った場合の level 1）は違反ではない。
        // 違反一覧に積むと、API を足すたびに「新規の違反」が並ぶ
        List<IdentifiedFinding> violations = findings.stream()
                .filter(f -> f.finding().severity() == Severity.HIGH
                        || f.finding().severity() == Severity.MEDIUM)
                .toList();

        Judgement judgement = judge(breaking, warnings, maximum);
        return List.of(MetricResult.of(metricId(), null, judgement.status(),
                BigDecimal.valueOf(breaking), UNIT, threshold, judgement.reason(), detail,
                violations));
    }

    private static Judgement judge(long breaking, long warnings, int maximum) {
        if (breaking > maximum) {
            return new Judgement(MeasurementStatus.FAIL,
                    "後方互換性を壊す変更が %d 件あります。".formatted(breaking)
                            + "意図した変更なら API のバージョンを上げてください（/v1 → /v2）");
        }
        if (warnings > 0) {
            return new Judgement(MeasurementStatus.WARN,
                    "破壊的になりうる変更が %d 件あります。利用側への影響を確認してください"
                            .formatted(warnings));
        }
        return new Judgement(MeasurementStatus.PASS, breaking == 0
                ? "後方互換性を壊す変更はありません"
                : "後方互換性を壊す変更が %d 件ありますが、合格ライン %d 件以内です"
                        .formatted(breaking, maximum));
    }

    private static long count(List<IdentifiedFinding> findings, Severity severity) {
        return findings.stream().filter(f -> f.finding().severity() == severity).count();
    }

    private record Judgement(MeasurementStatus status, String reason) {
    }
}
