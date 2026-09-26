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
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.domain.repo.WaiverRepository;
import com.qualitygate.platform.id.Uuid7;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final WaiverRepository waivers;
    private final List<MetricEvaluator> evaluators;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @SuppressWarnings("java:S107")
    public RunEvaluationService(RunRepository runs, RunSkippedMetricRepository skippedMetrics,
                                MeasurementRepository measurements, FindingRepository findings,
                                RepositorySummaryRepository summaries, WaiverRepository waivers,
                                List<MetricEvaluator> evaluators, ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.runs = runs;
        this.skippedMetrics = skippedMetrics;
        this.measurements = measurements;
        this.findings = findings;
        this.summaries = summaries;
        this.waivers = waivers;
        this.evaluators = evaluators;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
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
        Map<String, BigDecimal> previousValues = previousValuesOf(baseline);

        // 免除は判定の直前に適用する（docs/initial/05-architecture.md 3.2 の 5）。
        // 判定時点で有効なものだけを使うため、期限切れは自動的に再びカウントされる
        Instant now = Instant.now();
        ActiveWaivers active = ActiveWaivers.of(waivers.findEffective(run.getRepositoryId(), now));
        Map<String, Integer> pastBase = pastBaseComplexity(run, input);
        EvaluationContext context = new EvaluationContext(run, thresholds,
                active.removeFrom(input), previousValues, baseline.isPresent(), pastBase);
        List<MetricResult> results = evaluateAll(context);
        if (active.coversFindings()) {
            // 免除した違反も一覧に残す（免除は解決ではない）。何が違反かは評価器が決めるため、
            // 免除を適用しない入力でも判定し、その違反を免除の印つきで保存する
            List<MetricResult> unwaived = evaluateAll(new EvaluationContext(run, thresholds,
                    input, previousValues, baseline.isPresent(), pastBase));
            results = active.restoreWaivedFindings(results, unwaived);
        }
        results = active.applyMetricWaivers(results);

        // 再評価でも重複しないよう、この Run の既存の判定結果を置き換える
        measurements.deleteByRunId(runId);
        findings.deleteByRunId(runId);
        measurements.flush();
        findings.flush();

        persistMeasurements(run, results, context);
        persistFindings(run, results, baseline, active, input);

        Verdict verdict = aggregate(results);
        Completeness completeness = completenessOf(results);
        run.markEvaluated(verdict, completeness, Instant.now());

        updateSummary(run, results, verdict, completeness, now, active);

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
            results.addAll(evaluateMetric(metricId, byMetric.get(metricId), declared, context).stream()
                    .map(RunEvaluationService::noteReferenceOnly).toList());
        }
        return results;
    }

    /** 参考値の指標が計測できなかったときは、合否に影響しないことを理由に添える。 */
    private static MetricResult noteReferenceOnly(MetricResult result) {
        if (result.status() != MeasurementStatus.ERROR || !MetricCatalog.isReferenceOnly(result.metricId())) {
            return result;
        }
        return new MetricResult(result.metricId(), result.componentName(), result.status(), result.value(),
                result.unit(), result.threshold(),
                result.reason() + "（参考値の指標のため、Run の合否には影響しません）",
                result.detail(), result.findingsToPersist(), result.variant());
    }

    /**
     * 指標 1 件の判定。優先順位は docs/initial/05-architecture.md 6.2 に従う。
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
                    "成果物が提出されていません。CI（または収集ランナー）から送信されているか確認してください"
                            + "（意図的に計測しない場合は skippedMetrics で申告してください）"));
        }

        if (evaluator == null) {
            return List.of(MetricResult.error(metricId, "この指標の判定は未実装です"));
        }

        // 指標ごとの判定時間（qg.evaluation.duration。NFR 10.1 の監視用）
        Timer.Sample sample = Timer.start(meterRegistry);
        List<MetricResult> results = evaluator.evaluate(context);
        sample.stop(meterRegistry.timer("qg.evaluation.duration", "metric_id", metricId));
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
     *
     * <p>前回値は計測条件（{@code variant}）の一致するものに限る。条件の違う値との
     * 差は、改善や悪化ではなく条件の違いを表すだけだからである。
     */
    private void persistMeasurements(Run run, List<MetricResult> results,
                                     EvaluationContext context) {
        for (MetricResult result : results) {
            BigDecimal previous = context.previousValue(result.metricId(),
                    result.componentName(), result.variant()).orElse(null);
            measurements.save(new Measurement(Uuid7.generate(), run.getId(),
                    run.getRepositoryId(), result.metricId(), result.componentName(),
                    result.variant(), result.status(), result.value(), result.unit(),
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
    private void persistFindings(Run run, List<MetricResult> results, Optional<Run> baseline,
                                 ActiveWaivers active, NormalizedInput input) {
        Set<String> baselineFingerprints = baseline
                .map(b -> Set.copyOf(findings.findActiveFingerprints(b.getId())))
                .orElse(Set.of());

        Set<String> current = new HashSet<>();
        for (MetricResult result : results) {
            for (IdentifiedFinding finding : result.findingsToPersist()) {
                current.add(finding.fingerprint());
                // ファイルを移動・リネームしただけの違反は、移動前の fingerprint で比較対象と突き合わせる
                // （指標仕様書 0.4）。比較対象の移動前の違反は「解消」にしない
                String previous = input.previousFingerprintOf(finding.fingerprint());
                boolean moved = previous != null && baselineFingerprints.contains(previous)
                        && !baselineFingerprints.contains(finding.fingerprint());
                if (moved) {
                    current.add(previous);
                }
                FindingState state = stateOf(moved ? previous : finding.fingerprint(), baselineFingerprints,
                        baseline.isPresent());
                Finding entity = toEntity(run, finding, state);
                active.waiverOf(finding).ifPresent(entity::applyWaiver);
                findings.save(entity);
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

    /**
     * FAIL・ERROR があれば不合格。SKIP・REFERENCE・NOT_APPLICABLE は集約に影響しない。
     * 参考値の指標（{@link MetricCatalog#isReferenceOnly}）は、計測できなかった（ERROR）場合も影響しない。
     */
    static Verdict aggregate(List<MetricResult> results) {
        List<MetricResult> judged = judged(results);
        boolean failed = judged.stream().anyMatch(r ->
                r.status() == MeasurementStatus.FAIL || r.status() == MeasurementStatus.ERROR);
        if (failed) {
            return Verdict.FAIL;
        }
        return judged.stream().anyMatch(r -> r.status() == MeasurementStatus.WARN)
                ? Verdict.PASS_WITH_WARNINGS
                : Verdict.PASS;
    }

    /** 合否に使う判定結果。参考値の指標を除く。 */
    private static List<MetricResult> judged(List<MetricResult> results) {
        return results.stream().filter(r -> !MetricCatalog.isReferenceOnly(r.metricId())).toList();
    }

    /**
     * SKIP / REFERENCE を 1 つでも含めば部分計測とする。
     *
     * <p>NOT_APPLICABLE は含めない。ツールの制約で測りようのないものを部分計測に
     * 数えると、どの Run も永遠に完全計測にならず、部分計測の警告が意味を失う。
     */
    static Completeness completenessOf(List<MetricResult> results) {
        // 参考値の指標は常に REFERENCE のため含めない。含めるとどの Run も部分計測になる
        boolean partial = judged(results).stream().anyMatch(r ->
                r.status() == MeasurementStatus.SKIP || r.status() == MeasurementStatus.REFERENCE);
        return partial ? Completeness.PARTIAL : Completeness.FULL;
    }

    /**
     * M-07 の比較元を、比較元コミットで判定済みの過去の Run から求める（指標仕様書 M-07「ベース側の CC 取得」の 2）。
     *
     * <p>比較元の解析結果（scope=base）が送られていて、Run に比較元コミットがあり、そのコミットの Run で M-07 が
     * 判定されている場合だけ使う。過去の Run が保存しているのは注意水準（{@code warn_from}）以上の関数だけだが、
     * 判定に要るのは「合格ラインを超えた関数が比較元より悪化したか」なので足りる。保存されていない関数は
     * 比較元で注意水準未満（または存在しない）であり、合格ラインを超えた今の CC より必ず小さい。
     *
     * @return fingerprint → CC。使えなければ null（比較元なしとして判定する）
     */
    private Map<String, Integer> pastBaseComplexity(Run run, NormalizedInput input) {
        if (!input.baseFindingsOf(GateThresholds.M_COMPLEXITY).isEmpty() || run.getBaseCommitSha() == null
                || run.getBaseCommitSha().equals(run.getCommitSha())) {
            return null;
        }
        Optional<Run> baseRun = runs.findFirstByRepositoryIdAndCommitShaAndStatusOrderByAttemptDesc(
                run.getRepositoryId(), run.getBaseCommitSha(), RunStatus.EVALUATED);
        boolean judged = baseRun.map(base -> measurements.findByRunId(base.getId()).stream()
                        .anyMatch(m -> GateThresholds.M_COMPLEXITY.equals(m.getMetricId())
                                && JUDGED.contains(m.getStatus())))
                .orElse(false);
        if (!judged) {
            return null;
        }
        Map<String, Integer> complexity = new HashMap<>();
        for (Finding finding : findings.findByRunId(baseRun.get().getId())) {
            if (GateThresholds.M_COMPLEXITY.equals(finding.getMetricId())) {
                Object value = objectMapper.readValue(finding.getDetail(), Map.class).get("complexity");
                if (value instanceof Number number) {
                    complexity.put(finding.getFingerprint(), number.intValue());
                }
            }
        }
        return complexity;
    }

    /** M-07 が値をもって判定された状態。ERROR・SKIP などの Run は比較元に使わない。 */
    private static final Set<MeasurementStatus> JUDGED =
            Set.of(MeasurementStatus.PASS, MeasurementStatus.WARN, MeasurementStatus.FAIL);

    /**
     * 比較対象 Run。同一ブランチで、この Run より前に計測された判定済みの Run。
     *
     * <p>再評価では前回決めた比較対象を使い続ける。後から計測された Run を比較対象に
     * すると、過去の Run の「新規 / 解消」が未来の Run との比較に変わってしまう。
     */
    public Optional<Run> findBaseline(Run run) {
        if (run.getBaselineRunId() != null) {
            Optional<Run> previous = runs.findById(run.getBaselineRunId());
            if (previous.isPresent()) {
                return previous;
            }
        }
        return runs.findFirstByRepositoryIdAndBranchAndStatusAndMeasuredAtLessThanOrderByMeasuredAtDesc(
                        run.getRepositoryId(), run.getBranch(),
                        com.qualitygate.domain.model.RunStatus.EVALUATED, run.getMeasuredAt())
                .filter(candidate -> !candidate.getId().equals(run.getId()));
    }

    private Map<String, BigDecimal> previousValuesOf(Optional<Run> baseline) {
        if (baseline.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> values = new HashMap<>();
        for (Measurement measurement : measurements.findByRunId(baseline.get().getId())) {
            values.put(EvaluationContext.key(measurement.getMetricId(),
                    measurement.getComponentName(), measurement.getVariant()),
                    measurement.getValue());
        }
        return values;
    }

    /**
     * 読み取りモデルを更新する。
     *
     * <p>最新の Run より古い Run（過去の Run の再評価、遅れて届いた Run）では
     * 最新の判定を書き換えない。ダッシュボードが過去の状態に巻き戻って見えるため。
     * 免除の件数だけは常に現在値に更新する。
     */
    private void updateSummary(Run run, List<MetricResult> results, Verdict verdict,
                               Completeness completeness, Instant now, ActiveWaivers active) {
        RepositorySummary summary = summaries.findById(run.getRepositoryId())
                .orElseGet(() -> summaries.save(new RepositorySummary(run.getRepositoryId())));
        int activeWaivers = (int) waivers.countEffective(run.getRepositoryId(), now);

        boolean isLatest = summary.getLatestMeasuredAt() == null
                || !run.getMeasuredAt().isBefore(summary.getLatestMeasuredAt())
                || run.getId().equals(summary.getLatestRunId());
        if (!isLatest) {
            summary.updateWaiverCount(activeWaivers);
            summaries.save(summary);
            return;
        }

        long critical = countBySeverity(results, Severity.CRITICAL, active);
        long high = countBySeverity(results, Severity.HIGH, active);

        summary.update(run.getId(), verdict, completeness, run.getMeasuredAt(),
                toJson(categoryStatusOf(results)), (int) critical, (int) high, activeWaivers);
        summaries.save(summary);
    }

    /**
     * カテゴリ別の状態。カテゴリ内で最も重いステータスを代表にする。
     * 画面側で分類しないのは、カテゴリの定義がサーバとクライアントの 2 箇所に
     * 存在する状態を避けるため。
     */
    static Map<String, String> categoryStatusOf(List<MetricResult> results) {
        Map<MetricCategory, MeasurementStatus> worst = new EnumMap<>(MetricCategory.class);
        for (MetricResult result : judged(results)) {
            MetricCategory category = MetricCatalog.of(result.metricId()).category();
            worst.merge(category, result.status(),
                    (a, b) -> severityRank(a) >= severityRank(b) ? a : b);
        }
        // 参考値の指標だけのカテゴリは参考値と示す。合否に使う指標があるカテゴリの状態は変えない
        for (MetricResult result : results) {
            if (MetricCatalog.isReferenceOnly(result.metricId())) {
                worst.putIfAbsent(MetricCatalog.of(result.metricId()).category(), MeasurementStatus.REFERENCE);
            }
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
            // 対象外はカテゴリの状態を左右しない。合格の指標と並んでいれば合格のまま
            case NOT_APPLICABLE -> -1;
        };
    }

    /**
     * ダッシュボードの「重大 N 件・高 N 件」は脆弱性（M-06）の件数に限る。
     * アクセシビリティ違反（M-10）も同じ深刻度で保存するため、混ぜると
     * 未解決の脆弱性が増えたように見える。
     */
    private static long countBySeverity(List<MetricResult> results, Severity severity,
                                        ActiveWaivers active) {
        // 免除中の違反は「未解決」に数えない。判定と同じ件数をダッシュボードに出す
        return results.stream()
                .filter(r -> GateThresholds.M_VULNERABILITIES.equals(r.metricId()))
                .flatMap(r -> r.findingsToPersist().stream())
                .filter(f -> f.finding().severity() == severity)
                .filter(f -> active.waiverOf(f).isEmpty())
                .count();
    }

    private String toJson(Object value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }
}
