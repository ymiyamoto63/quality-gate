package com.qualitygate.release;

import com.qualitygate.domain.entity.Finding;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.MetricGuide;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.config.QualityGateProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final MeasurementRepository measurements;
    private final FindingRepository findings;
    private final ReleaseRefResolver resolver;
    private final QualityGateProperties properties;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("java:S107")
    public ReleaseReportService(MonitoredRepositoryRepository repositories, RunRepository runs,
                                MeasurementRepository measurements, FindingRepository findings,
                                ReleaseRefResolver resolver, QualityGateProperties properties,
                                ObjectMapper objectMapper) {
        this.repositories = repositories;
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
        Optional<MonitoredRepository> repository = findRepository();
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

        List<ReleaseReportResponse.ReleaseMetric> rows = rowsOf(chosen, fullName);
        ReleaseReportResponse.ReleaseCounts counts = countsOf(rows);
        ReleaseDecision decision = chosen.getVerdict() == Verdict.PASS && counts.failed() == 0
                ? ReleaseDecision.RELEASABLE
                : ReleaseDecision.NOT_RELEASABLE;
        return new ReleaseReportResponse(
                fullName,
                commitSha,
                SourceLinks.commit(fullName, commitSha),
                decision,
                reasonOf(decision, counts, rows),
                new ReleaseReportResponse.ReleaseRun(chosen.getMeasuredAt(), chosen.getTags(),
                        chosen.getCiRunUrl(), chosen.getBaseCommitSha()),
                counts,
                rows,
                guidesOf(rows));
    }

    /** 判定の履歴。同じコミットを何度か計測していれば、最後の判定だけを出す。 */
    @Transactional(readOnly = true)
    public ReleaseHistoryResponse history() {
        Optional<MonitoredRepository> repository = findRepository();
        if (repository.isEmpty()) {
            return new ReleaseHistoryResponse(List.of());
        }
        List<Run> evaluated = runs.findByRepositoryIdAndStatusOrderByMeasuredAtDescAttemptDesc(
                repository.get().getId(), RunStatus.EVALUATED, PageRequest.of(0, HISTORY_LIMIT * 4));
        Set<String> seen = new LinkedHashSet<>();
        List<ReleaseHistoryResponse.ReleaseHistoryItem> items = evaluated.stream()
                .filter(run -> seen.add(run.getCommitSha()))
                .limit(HISTORY_LIMIT)
                .map(run -> new ReleaseHistoryResponse.ReleaseHistoryItem(run.getMeasuredAt(), run.getCommitSha(),
                        run.getTags(), run.getVerdict(),
                        run.getTags().isEmpty() ? run.getCommitSha() : run.getTags().getFirst()))
                .toList();
        return new ReleaseHistoryResponse(items);
    }

    private Optional<MonitoredRepository> findRepository() {
        String fullName = properties.repository();
        int slash = fullName.indexOf('/');
        return repositories.findByOwnerAndName(fullName.substring(0, slash), fullName.substring(slash + 1));
    }

    private static ReleaseReportResponse notMeasured(String fullName, String commitSha, String reason) {
        return new ReleaseReportResponse(fullName, commitSha,
                commitSha == null ? null : SourceLinks.commit(fullName, commitSha),
                ReleaseDecision.NOT_MEASURED, reason, null, new ReleaseReportResponse.ReleaseCounts(0, 0, 0),
                List.of(), List.of());
    }

    private static String reasonOf(ReleaseDecision decision, ReleaseReportResponse.ReleaseCounts counts,
                                   List<ReleaseReportResponse.ReleaseMetric> rows) {
        if (decision == ReleaseDecision.RELEASABLE) {
            return "判定した %d 件の指標が、すべて合格ラインを満たしています。".formatted(counts.judged());
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        rows.stream().filter(row -> row.status() != MeasurementStatus.PASS).forEach(row -> names.add(row.name()));
        return "%d 件の指標が合格ラインを満たしていません（%s）。".formatted(counts.failed(),
                names.isEmpty() ? "—" : String.join("、", names));
    }

    private List<ReleaseReportResponse.ReleaseMetric> rowsOf(Run run, String fullName) {
        List<Measurement> judged = new ArrayList<>(measurements.findByRunId(run.getId()).stream()
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
        return new ReleaseReportResponse.ReleaseMetric(m.getMetricId(), MetricCatalog.of(m.getMetricId()).name(),
                m.getComponentName(),
                m.getVariant(), m.getStatus(),
                m.getValue(), m.getUnit(), ThresholdText.of(toMap(m.getThreshold()), m.getUnit()), m.getReason(),
                shown, related.size());
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

    private Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, JSON_OBJECT);
    }

    /** 合格ラインを「≥ 75%」のような文字列にする（画面の formatThreshold と同じ表記）。 */
    static final class ThresholdText {

        private ThresholdText() {
        }

        static String of(Map<String, Object> threshold, String unit) {
            Object operator = threshold.get("operator");
            Object value = threshold.get("value");
            if (!(operator instanceof String op) || value == null) {
                return null;
            }
            BigDecimal number;
            try {
                number = new BigDecimal(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
            if ("increase".equals(threshold.get("basis"))) {
                // M-10 は件数そのものではなく、比較元からの増加で判定する
                return "前回から +%s%s 以内".formatted(plain(number), suffix(unit));
            }
            return "%s %s%s".formatted(symbol(op), plain(number), suffix(unit));
        }

        static String plain(BigDecimal value) {
            return value.stripTrailingZeros().toPlainString();
        }

        private static String symbol(String operator) {
            return switch (operator) {
                case ">=" -> "≥";
                case "<=" -> "≤";
                default -> operator;
            };
        }

        static String suffix(String unit) {
            if (unit == null || unit.isEmpty()) {
                return "";
            }
            return switch (unit) {
                case "percent" -> "%";
                case "count" -> " 件";
                case "ms" -> "ms";
                case "score" -> " 点";
                default -> " " + unit;
            };
        }
    }
}
