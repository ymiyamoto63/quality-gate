package com.qualitygate.evaluate;

import com.qualitygate.domain.entity.Finding;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.RepositorySummary;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.RunSkippedMetric;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.MetricCategory;
import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.platform.id.Uuid7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 指標の判定・差分算出・保存をひとつのトランザクションで行う。
 *
 * <p>正規化（パース）は呼び出し側が事前に済ませる。DB のトランザクションを
 * ファイル読み取りの間ずっと保持しないためである。
 */
@Service
public class RunEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RunEvaluationService.class);

    private final RunRepository runs;
    private final RunSkippedMetricRepository skippedMetrics;
    private final MeasurementRepository measurements;
    private final FindingRepository findings;
    private final RepositorySummaryRepository summaries;
    private final List<MetricEvaluator> evaluators;
    private final ObjectMapper objectMapper;

    public RunEvaluationService(RunRepository runs, RunSkippedMetricRepository skippedMetrics,
                                MeasurementRepository measurements, FindingRepository findings,
                                RepositorySummaryRepository summaries,
                                List<MetricEvaluator> evaluators, ObjectMapper objectMapper) {
        this.runs = runs;
        this.skippedMetrics = skippedMetrics;
        this.measurements = measurements;
        this.findings = findings;
        this.summaries = summaries;
        this.evaluators = evaluators;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Run evaluate(UUID runId, NormalizedInput input, GateThresholds thresholds,
                        UUID gateConfigId) {
        Run run = runs.findById(runId).orElseThrow(
                () -> new IllegalStateException("Run が見つかりません: " + runId));
        run.markProcessing();
        // どの設定版で判定したかを残す。後からしきい値を変えても、
        // 過去の Run は当時の判定のまま保たれる。
        run.applyGateConfig(gateConfigId);

        Optional<Run> baseline = findBaseline(run);
        run.applyBaseline(baseline.map(Run::getId).orElse(null));
        EvaluationContext context = new EvaluationContext(run, thresholds, input,
                previousValuesOf(baseline), baseline.isPresent());

        List<MetricResult> results = evaluateAll(context);

        // 再評価でも重複しないよう、この Run の既存の判定結果を置き換える
        measurements.deleteByRunId(runId);
        findings.deleteByRunId(runId);
        measurements.flush();
        findings.flush();

        persistMeasurements(run, results, context);
        persistFindings(run, results, baseline);

        Verdict verdict = aggregate(results);
        Completeness completeness = completenessOf(results);
        run.markEvaluated(verdict, completeness, Instant.now());

        updateSummary(run, results, verdict, completeness);

        log.info("判定が完了しました runId={} verdict={} completeness={} 指標={}件",
                runId, verdict, completeness, results.size());
        return run;
    }

    private List<MetricResult> evaluateAll(EvaluationContext context) {
        Map<String, MetricEvaluator> byMetric = new HashMap<>();
        evaluators.forEach(e -> byMetric.put(e.metricId(), e));

        // 受理の可否はここで確定する。取り込み時点では設定が未解決だった。
        Map<String, RunSkippedMetric> declared = new HashMap<>();
        for (RunSkippedMetric skip : skippedMetrics.findByKeyRunId(context.run().getId())) {
            skip.decideAcceptance(
                    context.thresholds().skippableMetrics().contains(skip.getMetricId()));
            declared.put(skip.getMetricId(), skip);
        }

        List<MetricResult> results = new ArrayList<>();
        for (String metricId : context.thresholds().enabledMetrics().stream().sorted().toList()) {
            results.addAll(evaluateMetric(metricId, byMetric.get(metricId), declared, context));
        }
        return results;
    }

    /**
     * 指標 1 件の判定。優先順位は docs/05-architecture.md 6.2 に従う。
     *
     * <p>スキップ申告が {@code accepted=false} の場合は SKIP ではなく ERROR とする。
     * CI が自由にスキップを主張できると fail-closed が骨抜きになるためである。
     */
    private List<MetricResult> evaluateMetric(String metricId, MetricEvaluator evaluator,
                                              Map<String, RunSkippedMetric> declared,
                                              EvaluationContext context) {
        RunSkippedMetric skip = declared.get(metricId);
        if (skip != null) {
            return List.of(skip.isAccepted()
                    ? MetricResult.skipped(metricId, skip.getReason())
                    : MetricResult.error(metricId,
                            "スキップが申告されましたが、この指標はスキップを許容していません: "
                                    + skip.getReason()));
        }

        String parseError = context.input().parseErrors().get(metricId);
        if (parseError != null) {
            return List.of(MetricResult.error(metricId, "成果物を解釈できませんでした: " + parseError));
        }

        if (!context.input().metricsWithData().contains(metricId)) {
            // 申告のない未提出は不合格として扱う（fail-closed）。
            // 計測できていないものを合格扱いにすると、計測の破綻に気づけない。
            return List.of(MetricResult.error(metricId,
                    "成果物が提出されていません。CI から送信されているか確認してください"
                            + "（意図的に計測しない場合は skippedMetrics で申告してください）"));
        }

        if (evaluator == null) {
            return List.of(MetricResult.error(metricId, "この指標の判定は未実装です"));
        }

        List<MetricResult> results = evaluator.evaluate(context);
        return results.isEmpty()
                ? List.of(MetricResult.error(metricId, "成果物から値を取り出せませんでした"))
                : results;
    }

    /**
     * 判定結果を保存する。
     *
     * <p>比較対象 Run の値を {@code previousValue} として複製する。参照時に
     * 比較対象を引き直すのではなく Run に焼き付けるのは、比較対象が保持期間を
     * 過ぎて削除されても「前回比 +0.5」の表示が壊れないようにするためである。
     */
    private void persistMeasurements(Run run, List<MetricResult> results,
                                     EvaluationContext context) {
        for (MetricResult result : results) {
            BigDecimal previous = context
                    .previousValue(result.metricId(), result.componentName()).orElse(null);
            measurements.save(new Measurement(Uuid7.generate(), run.getId(),
                    run.getRepositoryId(), result.metricId(), result.componentName(),
                    result.status(), result.value(), result.unit(),
                    toJson(result.threshold()),
                    previous, result.reason(), toJson(result.detail()), run.getMeasuredAt()));
        }
    }

    /**
     * 違反を保存し、比較対象 Run との差分から状態を決める。
     *
     * <p>解消された違反も {@link FindingState#RESOLVED} として保存する。Run を
     * 不変のスナップショットに保ち、比較対象が削除されても表示が壊れないようにするため。
     */
    private void persistFindings(Run run, List<MetricResult> results, Optional<Run> baseline) {
        Set<String> baselineFingerprints = baseline
                .map(b -> Set.copyOf(findings.findActiveFingerprints(b.getId())))
                .orElse(Set.of());

        Set<String> current = new HashSet<>();
        for (MetricResult result : results) {
            for (IdentifiedFinding finding : result.findingsToPersist()) {
                current.add(finding.fingerprint());
                FindingState state = stateOf(finding.fingerprint(), baselineFingerprints,
                        baseline.isPresent());
                findings.save(toEntity(run, finding, state));
            }
        }

        // 比較対象に存在し、今回は無くなったものが「解消された違反」。
        // 現在の Run に属する行として保存する。
        baseline.ifPresent(b -> saveResolved(run, b, current));
    }

    private void saveResolved(Run run, Run baseline, Set<String> current) {
        for (Finding previous : findings.findByRunId(baseline.getId())) {
            if (previous.getState() == FindingState.RESOLVED
                    || current.contains(previous.getFingerprint())) {
                continue;
            }
            findings.save(new Finding(Uuid7.generate(), run.getId(), previous.getMetricId(),
                    previous.getFingerprint(), FindingState.RESOLVED, previous.getSeverity(),
                    previous.getRuleId(), previous.getTitle(), previous.getFilePath(),
                    previous.getLine(), previous.getComponentName(), previous.getDetail()));
        }
    }

    /**
     * 初回 Run では既存の違反をすべて NEW とせず {@link FindingState#INITIAL} にする。
     * 「この変更が大量の問題を持ち込んだ」という誤った印象を与えないため。
     */
    private static FindingState stateOf(String fingerprint, Set<String> baselineFingerprints,
                                        boolean hasBaseline) {
        if (!hasBaseline) {
            return FindingState.INITIAL;
        }
        return baselineFingerprints.contains(fingerprint)
                ? FindingState.CONTINUING
                : FindingState.NEW;
    }

    private Finding toEntity(Run run, IdentifiedFinding identified, FindingState state) {
        var finding = identified.finding();
        return new Finding(Uuid7.generate(), run.getId(), finding.metricId(),
                identified.fingerprint(), state, finding.severity(), finding.ruleId(),
                finding.title(), finding.filePath(), finding.line(),
                finding.componentName(), toJson(finding.detail()));
    }

    /** FAIL・ERROR があれば不合格。SKIP と REFERENCE は集約に影響しない。 */
    static Verdict aggregate(List<MetricResult> results) {
        boolean failed = results.stream().anyMatch(r ->
                r.status() == MeasurementStatus.FAIL || r.status() == MeasurementStatus.ERROR);
        if (failed) {
            return Verdict.FAIL;
        }
        return results.stream().anyMatch(r -> r.status() == MeasurementStatus.WARN)
                ? Verdict.PASS_WITH_WARNINGS
                : Verdict.PASS;
    }

    /** SKIP / REFERENCE を 1 つでも含めば部分計測とする。 */
    static Completeness completenessOf(List<MetricResult> results) {
        boolean partial = results.stream().anyMatch(r ->
                r.status() == MeasurementStatus.SKIP || r.status() == MeasurementStatus.REFERENCE);
        return partial ? Completeness.PARTIAL : Completeness.FULL;
    }

    private Optional<Run> findBaseline(Run run) {
        return runs.findFirstByRepositoryIdAndBranchAndStatusOrderByMeasuredAtDesc(
                        run.getRepositoryId(), run.getBranch(),
                        com.qualitygate.domain.model.RunStatus.EVALUATED)
                .filter(candidate -> !candidate.getId().equals(run.getId()));
    }

    private Map<String, BigDecimal> previousValuesOf(Optional<Run> baseline) {
        if (baseline.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> values = new HashMap<>();
        for (Measurement measurement : measurements.findByRunId(baseline.get().getId())) {
            values.put(EvaluationContext.key(measurement.getMetricId(),
                    measurement.getComponentName()), measurement.getValue());
        }
        return values;
    }

    private void updateSummary(Run run, List<MetricResult> results, Verdict verdict,
                               Completeness completeness) {
        RepositorySummary summary = summaries.findById(run.getRepositoryId())
                .orElseGet(() -> summaries.save(new RepositorySummary(run.getRepositoryId())));

        long critical = countBySeverity(results, Severity.CRITICAL);
        long high = countBySeverity(results, Severity.HIGH);

        summary.update(run.getId(), verdict, completeness, run.getMeasuredAt(),
                toJson(categoryStatusOf(results)), (int) critical, (int) high, 0);
        summaries.save(summary);
    }

    /**
     * カテゴリ別の状態。カテゴリ内で最も重いステータスを代表にする。
     * 画面側で分類しないのは、カテゴリの定義がサーバとクライアントの 2 箇所に
     * 存在する状態を避けるため。
     */
    static Map<String, String> categoryStatusOf(List<MetricResult> results) {
        Map<MetricCategory, MeasurementStatus> worst = new EnumMap<>(MetricCategory.class);
        for (MetricResult result : results) {
            MetricCategory category = MetricCatalog.of(result.metricId()).category();
            worst.merge(category, result.status(),
                    (a, b) -> severityRank(a) >= severityRank(b) ? a : b);
        }
        // EnumMap の反復順は宣言順、つまり要件定義の指標表と同じ並びになる。
        Map<String, String> asString = new LinkedHashMap<>();
        worst.forEach((category, status) -> asString.put(category.displayName(), status.name()));
        return asString;
    }

    private static int severityRank(MeasurementStatus status) {
        return switch (status) {
            case ERROR -> 5;
            case FAIL -> 4;
            case WARN -> 3;
            case REFERENCE -> 2;
            case SKIP -> 1;
            case PASS -> 0;
        };
    }

    private static long countBySeverity(List<MetricResult> results, Severity severity) {
        return results.stream()
                .flatMap(r -> r.findingsToPersist().stream())
                .filter(f -> f.finding().severity() == severity)
                .count();
    }

    private String toJson(Object value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }
}
