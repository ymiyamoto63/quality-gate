package com.qualitygate.release;

import com.qualitygate.domain.entity.Finding;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.MetricGuide;
import com.qualitygate.domain.metric.Threshold;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.config.QualityGateProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * リリース判定。指定したタグ・コミット（指定が無ければ最新の計測）で判定済みの Run から、
 * リリースしてよいかの結論を組み立てる。
 *
 * <p>判定し直しはしない。Run の判定時に確定した結果（その時点の合格ライン）をそのまま使う。
 * 見るたびに結論が変わると、リリース判定の証跡にならない。
 *
 * <p>使う Run は、そのコミットの最新の判定済みの Run。近くのコミットの Run では代用しない。別のコードの結果になるため。
 */
@Service
public class ReleaseReportService {

    /** 不合格の指標ごとに示す違反の上限。全件は開発者が収集ランナーのレポートで見る。 */
    static final int MAX_FINDINGS = 10;
    /** 履歴に出す件数。 */
    static final int HISTORY_LIMIT = 50;
    /** 設定で判定から外した指標の、対象外の理由。 */
    static final String DISABLED_REASON = "設定（QG_DISABLED_METRICS）で判定から外しています";

    private static final TypeReference<Map<String, Object>> DETAIL = new TypeReference<>() {
    };

    private final EvaluatedRuns evaluatedRuns;
    private final RunRepository runs;
    private final MeasurementRepository measurements;
    private final FindingRepository findings;
    private final ReleaseRefResolver resolver;
    private final QualityGateProperties properties;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("java:S107")
    ReleaseReportService(EvaluatedRuns evaluatedRuns, RunRepository runs,
                         MeasurementRepository measurements, FindingRepository findings,
                         ReleaseRefResolver resolver, QualityGateProperties properties,
                         ObjectMapper objectMapper) {
        this.evaluatedRuns = evaluatedRuns;
        this.runs = runs;
        this.measurements = measurements;
        this.findings = findings;
        this.resolver = resolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** @param ref タグかコミット SHA。空なら最新の判定済みの計測 */
    @Transactional(readOnly = true)
    public ReleaseReportResponse report(String ref) {
        String fullName = properties.repository();
        Optional<MonitoredRepository> repository = evaluatedRuns.repository();
        boolean latest = ref == null || ref.isBlank();

        if (repository.isEmpty()) {
            if (!latest) {
                // 指定の検証（空白や使えない文字）は、計測が無くても同じように返す
                resolver.validate(ref);
            }
            return notMeasured(fullName, null,
                    "まだ 1 度も計測されていません。収集ランナーで計測してから、もう一度確認してください。");
        }

        List<Run> candidates;
        String commitSha;
        if (latest) {
            Optional<Run> newest = runs.findFirstByRepositoryIdAndStatusOrderByMeasuredAtDescAttemptDesc(
                    repository.get().getId(), RunStatus.EVALUATED);
            if (newest.isEmpty()) {
                return notMeasured(fullName, null,
                        "判定まで終わった計測がありません。収集ランナーで計測してから、もう一度確認してください。");
            }
            commitSha = newest.get().getCommitSha();
            candidates = List.of(newest.get());
        } else {
            commitSha = resolver.resolve(repository.get(), ref);
            candidates = runs.findByRepositoryIdAndCommitShaOrderByMeasuredAtDescAttemptDesc(
                    repository.get().getId(), commitSha);
        }

        Run chosen = candidates.stream().filter(run -> run.getStatus() == RunStatus.EVALUATED)
                .findFirst().orElse(null);
        if (chosen == null) {
            return notMeasured(fullName, commitSha, candidates.isEmpty()
                    ? "このコミットはまだ計測されていません。収集ランナーの commit にタグかコミットを指定して計測してから、もう一度確認してください。"
                    : "このコミットの計測（%d 件）は、どれも判定まで終わっていません（処理の失敗など）。計測し直してください。"
                            .formatted(candidates.size()));
        }

        List<Measurement> results = measurements.findByRunId(chosen.getId());
        List<ReleaseReportResponse.ReleaseMetric> rows = rowsOf(chosen, results, fullName);
        List<ReleaseReportResponse.ReleaseNotJudgedMetric> notJudged = notJudgedOf(chosen, results);
        ReleaseReportResponse.ReleaseCounts counts = countsOf(rows);
        ReleaseDecision decision = chosen.getVerdict() == Verdict.PASS && counts.failed() == 0
                ? ReleaseDecision.RELEASABLE
                : ReleaseDecision.NOT_RELEASABLE;
        return new ReleaseReportResponse(
                fullName,
                commitSha,
                SourceLinks.commit(fullName, commitSha),
                decision,
                reasonOf(decision, counts, rows, notJudged),
                new ReleaseReportResponse.ReleaseRun(chosen.getMeasuredAt(), chosen.getTags(),
                        chosen.getCiRunUrl(), chosen.getBaseCommitSha()),
                counts,
                rows,
                notJudged,
                ReleaseConditions.of(chosen).orElse(null),
                guidesOf(rows));
    }

    /** 判定の履歴。同じコミットを何度か計測していれば、最後の判定だけを出す。 */
    @Transactional(readOnly = true)
    public ReleaseHistoryResponse history() {
        Optional<MonitoredRepository> repository = evaluatedRuns.repository();
        if (repository.isEmpty()) {
            return new ReleaseHistoryResponse(List.of());
        }
        List<Run> latest = evaluatedRuns.latestPerCommit(repository.get(), HISTORY_LIMIT);
        // 判定の範囲は Run をまたいで 1 回の集計で読む（Run ごとに結果を読むと履歴の件数だけ問い合わせが増える）
        Map<UUID, MeasurementRepository.RunScopeSummary> summaries = new HashMap<>();
        if (!latest.isEmpty()) {
            measurements.summarizeScopeByRunIds(latest.stream().map(Run::getId).toList())
                    .forEach(summary -> summaries.put(summary.getRunId(), summary));
        }
        List<ReleaseHistoryResponse.ReleaseHistoryItem> items = latest.stream()
                .map(run -> historyItemOf(run, summaries.get(run.getId())))
                .toList();
        return new ReleaseHistoryResponse(items);
    }

    /**
     * 履歴の 1 行。判定した件数と対象外の件数は、リリース判定（{@link #report}）の {@code counts.judged} と
     * {@code notJudged} と同じ数え方にする（設定で外した指標と、対象外の結果）。
     */
    private static ReleaseHistoryResponse.ReleaseHistoryItem historyItemOf(
            Run run, MeasurementRepository.RunScopeSummary summary) {
        int disabled = run.getDisabledMetrics().map(List::size).orElse(0);
        return new ReleaseHistoryResponse.ReleaseHistoryItem(run.getMeasuredAt(), run.getCommitSha(),
                run.getTags(), run.getVerdict(), refOf(run),
                summary == null ? 0 : (int) summary.getJudged(),
                disabled + (summary == null ? 0 : (int) summary.getNotApplicable()),
                summary != null && summary.getRelaxed(),
                summary != null && summary.getFilesExcluded());
    }

    /** リリース判定を開くときの指定。タグがあればタグ、無ければコミット SHA。 */
    static String refOf(Run run) {
        return run.getTags().isEmpty() ? run.getCommitSha() : run.getTags().getFirst();
    }

    private static ReleaseReportResponse notMeasured(String fullName, String commitSha, String reason) {
        return new ReleaseReportResponse(fullName, commitSha,
                commitSha == null ? null : SourceLinks.commit(fullName, commitSha),
                ReleaseDecision.NOT_MEASURED, reason, null, new ReleaseReportResponse.ReleaseCounts(0, 0, 0),
                List.of(), List.of(), null, List.of());
    }

    /**
     * 結論の理由。判定の対象外にした指標があれば、その名前を必ず添える（DD-27）。
     * 「すべて合格」とだけ書くと、12 の指標のうち 2 つしか判定していなくても、開発に携わっていない方には分からない。
     */
    private static String reasonOf(ReleaseDecision decision, ReleaseReportResponse.ReleaseCounts counts,
                                   List<ReleaseReportResponse.ReleaseMetric> rows,
                                   List<ReleaseReportResponse.ReleaseNotJudgedMetric> notJudged) {
        String excluded = String.join("、", notJudged.stream().map(ReleaseReportService::labelOf).toList());
        if (decision == ReleaseDecision.RELEASABLE) {
            return notJudged.isEmpty()
                    ? "判定した %d 件の指標が、すべて合格ラインを満たしています。".formatted(counts.judged())
                    : "判定した %d 件の指標が、すべて合格ラインを満たしています（%d 件は判定の対象外です: %s）。"
                            .formatted(counts.judged(), notJudged.size(), excluded);
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        rows.stream().filter(row -> row.status() != MeasurementStatus.PASS).forEach(row -> names.add(row.name()));
        String reason = "%d 件の指標が合格ラインを満たしていません（%s）。".formatted(counts.failed(),
                names.isEmpty() ? "—" : String.join("、", names));
        return notJudged.isEmpty() ? reason
                : reason + "ほかに %d 件は判定の対象外です（%s）。".formatted(notJudged.size(), excluded);
    }

    /** 判定の対象外の指標の呼び名（コンポーネントがあれば「ミューテーションスコア（frontend）」）。 */
    private static String labelOf(ReleaseReportResponse.ReleaseNotJudgedMetric metric) {
        return metric.componentName() == null ? metric.name()
                : "%s（%s）".formatted(metric.name(), metric.componentName());
    }

    /**
     * 判定の対象外にした指標。設定（{@code QG_DISABLED_METRICS}）で外した指標と、対象外（{@code NOT_APPLICABLE}）の結果。
     *
     * <p>判定から外した指標は、判定したときに Run に焼き付けたものを使う。今の設定を読むと、
     * 後から設定を変えたときに過去の判定の範囲が変わって見える。焼き付ける前（V003 より前）に判定した Run は、
     * 外した指標が分からないため「無し」として扱う。
     */
    private static List<ReleaseReportResponse.ReleaseNotJudgedMetric> notJudgedOf(Run run, List<Measurement> results) {
        List<ReleaseReportResponse.ReleaseNotJudgedMetric> notJudged = new ArrayList<>();
        run.getDisabledMetrics().orElse(List.of()).forEach(metricId -> notJudged.add(
                new ReleaseReportResponse.ReleaseNotJudgedMetric(metricId, MetricCatalog.of(metricId).name(), null,
                        DISABLED_REASON)));
        results.stream().filter(m -> !m.getStatus().affectsVerdict()).forEach(m -> notJudged.add(
                new ReleaseReportResponse.ReleaseNotJudgedMetric(m.getMetricId(),
                        MetricCatalog.of(m.getMetricId()).name(), m.getComponentName(),
                        Objects.requireNonNullElse(m.getReason(), "計測の対象外です"))));
        notJudged.sort(Comparator
                .comparing(ReleaseReportResponse.ReleaseNotJudgedMetric::metricId, MetricCatalog::compareByCatalogOrder)
                .thenComparing(metric -> Objects.toString(metric.componentName(), "")));
        return notJudged;
    }

    private List<ReleaseReportResponse.ReleaseMetric> rowsOf(Run run, List<Measurement> results, String fullName) {
        List<Measurement> judged = new ArrayList<>(results.stream()
                .filter(m -> m.getStatus().affectsVerdict())
                .toList());
        judged.sort(Comparator
                .comparing((Measurement m) -> m.getStatus() == MeasurementStatus.PASS ? 1 : 0)
                .thenComparing(Measurement::getMetricId, MetricCatalog::compareByCatalogOrder)
                .thenComparing(m -> Objects.toString(m.getComponentName(), ""))
                .thenComparing(m -> Objects.toString(m.getVariant(), "")));
        List<Finding> active = judged.stream().anyMatch(m -> m.getStatus() != MeasurementStatus.PASS)
                ? findings.findActiveByRunId(run.getId())
                : List.of();
        return judged.stream().map(m -> rowOf(m, active, run, fullName)).toList();
    }

    private ReleaseReportResponse.ReleaseMetric rowOf(Measurement m, List<Finding> active, Run run, String fullName) {
        List<Finding> related = m.getStatus() == MeasurementStatus.PASS ? List.of() : active.stream()
                .filter(f -> f.getMetricId().equals(m.getMetricId()))
                .filter(f -> m.getComponentName() == null || m.getComponentName().equals(f.getComponentName()))
                .sorted(Comparator.comparing(Finding::getSeverity)
                        .thenComparing(f -> Objects.toString(f.getFilePath(), ""))
                        .thenComparing(f -> f.getLine() == null ? 0 : f.getLine()))
                .toList();
        List<ReleaseReportResponse.ReleaseFinding> shown = related.stream().limit(MAX_FINDINGS)
                .map(f -> new ReleaseReportResponse.ReleaseFinding(f.getTitle(), locationOf(f),
                        SourceLinks.blob(fullName, run.getCommitSha(), f.getFilePath(), f.getLine())))
                .toList();
        Threshold threshold = Threshold.parse(objectMapper, m.getThreshold());
        return new ReleaseReportResponse.ReleaseMetric(m.getMetricId(), MetricCatalog.of(m.getMetricId()).name(),
                m.getComponentName(),
                m.getVariant(), m.getStatus(),
                m.getValue(), m.getUnit(), ThresholdText.of(threshold, m.getUnit()),
                threshold.isRelaxed(), ThresholdText.defaultOf(threshold, m.getUnit()),
                excludedFilesOf(m), m.getReason(),
                shown, related.size());
    }

    /**
     * {@code QG_EXCLUSIONS} で計測から外したファイルの数。内訳（{@code detail}）の {@code excludedFiles} を読む
     * （M-01 / M-02 / M-05 / M-06 / M-11 / M-12 が記録する。docs/metrics.md 2.3）。除外も合格ラインの緩和と同じく結果を良く見せうるため、画面に出す。
     */
    private int excludedFilesOf(Measurement m) {
        if (m.getDetail() == null || m.getDetail().isBlank()) {
            return 0;
        }
        return objectMapper.readValue(m.getDetail(), DETAIL).get("excludedFiles") instanceof Number number
                ? number.intValue() : 0;
    }

    private static String locationOf(Finding finding) {
        if (finding.getFilePath() == null || finding.getFilePath().isBlank()) {
            return null;
        }
        return finding.getLine() == null ? finding.getFilePath() : finding.getFilePath() + ":" + finding.getLine();
    }

    private static ReleaseReportResponse.ReleaseCounts countsOf(List<ReleaseReportResponse.ReleaseMetric> rows) {
        int passed = (int) rows.stream().filter(row -> row.status() == MeasurementStatus.PASS).count();
        return new ReleaseReportResponse.ReleaseCounts(rows.size(), passed, rows.size() - passed);
    }

    private static List<ReleaseReportResponse.ReleaseGuide> guidesOf(List<ReleaseReportResponse.ReleaseMetric> rows) {
        LinkedHashSet<String> metricIds = new LinkedHashSet<>();
        rows.forEach(row -> metricIds.add(row.metricId()));
        return metricIds.stream().map(metricId -> {
            MetricGuide guide = MetricGuide.of(metricId);
            return new ReleaseReportResponse.ReleaseGuide(metricId, MetricCatalog.of(metricId).name(), guide.summary(),
                    guide.basis().name(), guide.basis().label(), guide.rationale(), guide.risk(),
                    guide.definition(), guide.tools());
        }).toList();
    }
}
