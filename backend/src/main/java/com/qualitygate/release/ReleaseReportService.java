package com.qualitygate.release;

import com.qualitygate.domain.entity.GateConfig;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.MetricDefinition;
import com.qualitygate.domain.metric.MetricGuide;
import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * リリース判定（UC-10）。指定したタグ・コミットで判定済みの Run から、リリースしてよいかの結論を組み立てる。
 *
 * <p>判定し直しはしない。Run の判定時に確定した結果（その時点の合格ライン）をそのまま使う。
 * 見るたびに結論が変わると、リリース判定の証跡にならない。
 *
 * <p>使う Run は、そのコミットの<strong>最新の完全計測</strong>。完全計測が無ければ最新の Run を使い、
 * 部分計測で不合格が無ければ「判定できない」とする（測っていない指標を合格とみなさない）。
 * 近くのコミットの Run では代用しない。別のコードの結果になるため。
 */
@Service
public class ReleaseReportService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final MeasurementRepository measurements;
    private final GateConfigRepository gateConfigs;
    private final ReleaseRefResolver resolver;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("java:S107")
    public ReleaseReportService(MonitoredRepositoryRepository repositories, RunRepository runs,
                                MeasurementRepository measurements, GateConfigRepository gateConfigs,
                                ReleaseRefResolver resolver, AuditLogger auditLogger, ObjectMapper objectMapper) {
        this.repositories = repositories;
        this.runs = runs;
        this.measurements = measurements;
        this.gateConfigs = gateConfigs;
        this.resolver = resolver;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ReleaseReportResponse report(UUID repositoryId, String ref) {
        return build(repositoryId, ref);
    }

    /** CSV の出力用。出力したことを監査ログに残す（判定の証跡を、いつ誰が出したか）。 */
    @Transactional
    public ReleaseReportResponse reportForExport(UUID repositoryId, String ref, Actor actor) {
        ReleaseReportResponse report = build(repositoryId, ref);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ref", report.ref());
        after.put("commitSha", report.commitSha());
        after.put("decision", report.decision().name());
        after.put("runId", report.run() == null ? null : report.run().runId().toString());
        auditLogger.record(actor, AuditAction.RELEASE_REPORT_EXPORTED, "REPOSITORY", repositoryId, null, after);
        return report;
    }

    private ReleaseReportResponse build(UUID repositoryId, String ref) {
        MonitoredRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> ApiException.notFound("Repository", repositoryId));
        ReleaseRefResolver.Resolved resolved = resolver.resolve(repository, ref);

        List<Run> candidates = runs.findByRepositoryIdAndCommitShaOrderByMeasuredAtDescAttemptDesc(
                repositoryId, resolved.commitSha());
        List<Run> evaluated = candidates.stream().filter(run -> run.getStatus() == RunStatus.EVALUATED).toList();
        Run chosen = evaluated.stream().filter(run -> run.getCompleteness() == Completeness.FULL).findFirst()
                .orElse(evaluated.isEmpty() ? null : evaluated.getFirst());

        List<ReleaseReportResponse.ReleaseMetric> rows = chosen == null ? List.of() : rowsOf(chosen);
        ReleaseReportResponse.ReleaseCounts counts = countsOf(rows);
        String commitUrl = "https://github.com/%s/commit/%s".formatted(repository.fullName(),
                URLEncoder.encode(resolved.commitSha(), StandardCharsets.UTF_8));

        return new ReleaseReportResponse(
                repositoryId,
                repository.fullName(),
                resolved.ref(),
                resolved.type(),
                resolved.commitSha(),
                commitUrl,
                decisionOf(chosen, counts),
                reasonOf(chosen, candidates.size(), counts, rows),
                chosen == null ? null : new ReleaseReportResponse.ReleaseRun(chosen.getId(), chosen.getMeasuredAt(),
                        chosen.getBranch(), chosen.getAttempt(), chosen.getVerdict(), chosen.getCompleteness()),
                chosen == null ? candidates.size() : candidates.size() - 1,
                chosen == null ? null : gateConfigOf(chosen),
                counts,
                rows,
                guidesOf(rows));
    }

    static ReleaseDecision decisionOf(Run run, ReleaseReportResponse.ReleaseCounts counts) {
        if (run == null) {
            return ReleaseDecision.UNDETERMINED;
        }
        // 測った範囲に不合格があれば、部分計測でも結論は変わらない
        if (run.getVerdict() == Verdict.FAIL || counts.failed() + counts.errored() > 0) {
            return ReleaseDecision.NOT_RELEASABLE;
        }
        if (run.getCompleteness() != Completeness.FULL) {
            return ReleaseDecision.UNDETERMINED;
        }
        return run.getVerdict() == Verdict.PASS_WITH_WARNINGS || counts.warned() > 0
                ? ReleaseDecision.RELEASABLE_WITH_WARNINGS
                : ReleaseDecision.RELEASABLE;
    }

    private static String reasonOf(Run run, int candidateCount, ReleaseReportResponse.ReleaseCounts counts,
                                   List<ReleaseReportResponse.ReleaseMetric> rows) {
        if (run == null) {
            return candidateCount == 0
                    ? "このコミットはまだ計測されていません。収集ランナーで計測してから、もう一度確認してください。"
                    : "このコミットの計測（%d 件）は、どれも判定まで終わっていません（処理の失敗など）。計測し直してください。"
                            .formatted(candidateCount);
        }
        return switch (decisionOf(run, counts)) {
            case NOT_RELEASABLE -> "%d 件の指標が不合格です（%s）。".formatted(
                    counts.failed() + counts.errored(), namesOf(rows, MeasurementStatus.FAIL, MeasurementStatus.ERROR))
                    + (run.getCompleteness() == Completeness.FULL ? "" : "部分計測のため、測っていない指標もあります。");
            case UNDETERMINED -> "測っていない指標があるため判定できません（%s）。完全計測で計測し直してください。"
                    .formatted(namesOf(rows, MeasurementStatus.SKIP, MeasurementStatus.REFERENCE));
            case RELEASABLE_WITH_WARNINGS -> "不合格はありませんが、%d 件の指標が注意です（%s）。".formatted(
                    counts.warned(), namesOf(rows, MeasurementStatus.WARN));
            case RELEASABLE -> "合否に使う %d 件の指標がすべて合格です。".formatted(counts.judged());
        };
    }

    /** 該当した指標の名前（重複なし、参考値の指標を除く）。 */
    private static String namesOf(List<ReleaseReportResponse.ReleaseMetric> rows, MeasurementStatus... statuses) {
        List<MeasurementStatus> targets = List.of(statuses);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        rows.stream().filter(row -> !row.referenceOnly() && targets.contains(row.status()))
                .forEach(row -> names.add(row.name()));
        return names.isEmpty() ? "—" : String.join("、", names);
    }

    private List<ReleaseReportResponse.ReleaseMetric> rowsOf(Run run) {
        List<Measurement> rows = new ArrayList<>(measurements.findByRunId(run.getId()));
        rows.sort(Comparator
                .comparing((Measurement m) -> MetricCatalog.isReferenceOnly(m.getMetricId()))
                .thenComparing(m -> attentionRank(m.getStatus()))
                .thenComparing(Measurement::getMetricId, MetricCatalog::compareByCatalogOrder)
                .thenComparing(m -> Objects.toString(m.getComponentName(), ""))
                .thenComparing(m -> Objects.toString(m.getVariant(), ""))
                .thenComparing(m -> Objects.toString(m.getScenario(), "")));
        return rows.stream().map(this::rowOf).toList();
    }

    /** 先に見るべき順。不合格・計測エラー、注意、未計測、合格、対象外。 */
    private static int attentionRank(MeasurementStatus status) {
        return switch (status) {
            case FAIL, ERROR -> 0;
            case WARN -> 1;
            case SKIP -> 2;
            case PASS -> 3;
            case REFERENCE -> 4;
            case NOT_APPLICABLE -> 5;
        };
    }

    private ReleaseReportResponse.ReleaseMetric rowOf(Measurement m) {
        MetricDefinition definition = MetricCatalog.of(m.getMetricId());
        return new ReleaseReportResponse.ReleaseMetric(m.getMetricId(), definition.name(),
                definition.category().displayName(), m.getComponentName(),
                MetricCatalog.variantLabel(m.getMetricId(), m.getVariant()), m.getScenario(), m.getStatus(),
                m.getValue(), m.getUnit(), ThresholdText.of(toMap(m.getThreshold()), m.getUnit()), m.getReason(),
                definition.referenceOnly());
    }

    private static ReleaseReportResponse.ReleaseCounts countsOf(List<ReleaseReportResponse.ReleaseMetric> rows) {
        int passed = 0;
        int warned = 0;
        int failed = 0;
        int errored = 0;
        int skipped = 0;
        for (ReleaseReportResponse.ReleaseMetric row : rows) {
            if (row.referenceOnly()) {
                continue;
            }
            switch (row.status()) {
                case PASS -> passed++;
                case WARN -> warned++;
                case FAIL -> failed++;
                case ERROR -> errored++;
                case SKIP, REFERENCE -> skipped++;
                case NOT_APPLICABLE -> {
                    // 測りようがないものは数えない（部分計測の理由にもならない）
                }
            }
        }
        return new ReleaseReportResponse.ReleaseCounts(passed + warned + failed + errored, passed, warned, failed, errored,
                skipped);
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

    private ReleaseReportResponse.ReleaseGateConfig gateConfigOf(Run run) {
        if (run.getGateConfigId() == null) {
            return null;
        }
        Optional<GateConfig> config = gateConfigs.findById(run.getGateConfigId());
        return config.map(c -> new ReleaseReportResponse.ReleaseGateConfig(c.getVersion(), c.getSourceType(),
                c.getSourceCommitSha(), exclusionsOf(c))).orElse(null);
    }

    private List<String> exclusionsOf(GateConfig config) {
        if (config.getParsed() == null || config.getParsed().isBlank()) {
            return List.of();
        }
        Object exclusions = objectMapper.readValue(config.getParsed(), JSON_OBJECT).get("exclusions");
        return exclusions instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
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
