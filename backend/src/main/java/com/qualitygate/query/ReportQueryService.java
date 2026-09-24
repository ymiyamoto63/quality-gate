package com.qualitygate.query;

import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.query.dto.ReportResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 品質レポート（FR-08-4）。任意の期間・リポジトリについて、既定ブランチの判定をまとめる。
 *
 * <p>対象は既定ブランチの Run だけにする。PR やほかのブランチの Run を混ぜると、作業途中の不合格が
 * 「品質」として数えられてしまう。期間の日付は日次バッチと同じタイムゾーン（{@code QG_SCHEDULE_ZONE}）で区切る。
 */
@Service
public class ReportQueryService {

    /** 1 回に出せる期間の上限。これより長いと明細が大きくなりすぎる。 */
    static final int MAX_DAYS = 366;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final MeasurementRepository measurements;
    private final ZoneId zone;

    public ReportQueryService(MonitoredRepositoryRepository repositories, RunRepository runs,
                              MeasurementRepository measurements,
                              @Value("${quality-gate.schedule.zone:Asia/Tokyo}") String zone) {
        this.repositories = repositories;
        this.runs = runs;
        this.measurements = measurements;
        this.zone = ZoneId.of(zone);
    }

    /** 期間。省略時は今日までの 30 日間。 */
    public Period period(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(zone);
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "期間の開始（from）が終了（to）より後です");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "期間は %d 日以内で指定してください".formatted(MAX_DAYS));
        }
        return new Period(start, end, start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant());
    }

    @Transactional(readOnly = true)
    public ReportResponse summary(Period period, List<UUID> repositoryIds) {
        List<MonitoredRepository> targets = targets(repositoryIds);
        Map<UUID, List<Run>> runsByRepository = defaultBranchRuns(targets, period);
        Map<UUID, List<Measurement>> measurementsByRun = measurementsOf(runsByRepository.values().stream()
                .flatMap(List::stream).toList());

        List<ReportResponse.RepositoryReport> reports = targets.stream()
                .map(repository -> reportOf(repository, runsByRepository.getOrDefault(repository.getId(), List.of()),
                        measurementsByRun))
                .toList();
        return new ReportResponse(period.from(), period.to(), zone.getId(), reports);
    }

    /**
     * 明細の CSV。1 行が「Run × 指標（コンポーネント・計測条件）」。Excel で開けるよう UTF-8 の BOM を付ける。
     */
    @Transactional(readOnly = true)
    public void writeCsv(Period period, List<UUID> repositoryIds, Writer out) throws IOException {
        List<MonitoredRepository> targets = targets(repositoryIds);
        Map<UUID, List<Run>> runsByRepository = defaultBranchRuns(targets, period);
        Map<UUID, List<Measurement>> measurementsByRun = measurementsOf(runsByRepository.values().stream()
                .flatMap(List::stream).toList());

        out.write('﻿');
        writeRow(out, List.of("repository", "branch", "run_id", "measured_at", "commit_sha", "verdict",
                "completeness", "metric_id", "metric_name", "component", "variant", "status", "value", "unit",
                "previous_value", "threshold", "reason"));
        for (MonitoredRepository repository : targets) {
            for (Run run : runsByRepository.getOrDefault(repository.getId(), List.of())) {
                List<Measurement> rows = measurementsByRun.getOrDefault(run.getId(), List.of()).stream()
                        .sorted(MEASUREMENT_ORDER).toList();
                for (Measurement m : rows) {
                    writeRow(out, List.of(repository.fullName(), run.getBranch(), run.getId().toString(),
                            TIMESTAMP.format(run.getMeasuredAt().atZone(zone)), run.getCommitSha(),
                            text(run.getVerdict()), text(run.getCompleteness()), m.getMetricId(),
                            MetricCatalog.of(m.getMetricId()).name(), text(m.getComponentName()),
                            text(m.getVariant()), m.getStatus().name(), plain(m.getValue()), text(m.getUnit()),
                            plain(m.getPreviousValue()), text(m.getThreshold()), text(m.getReason())));
                }
            }
        }
        out.flush();
    }

    private static final Comparator<Measurement> MEASUREMENT_ORDER = Comparator
            .comparing(Measurement::getMetricId)
            .thenComparing(m -> Objects.toString(m.getComponentName(), ""))
            .thenComparing(m -> Objects.toString(m.getVariant(), ""));

    private ReportResponse.RepositoryReport reportOf(MonitoredRepository repository, List<Run> repositoryRuns,
                                                      Map<UUID, List<Measurement>> measurementsByRun) {
        int passed = count(repositoryRuns, Verdict.PASS);
        int warned = count(repositoryRuns, Verdict.PASS_WITH_WARNINGS);
        int failed = count(repositoryRuns, Verdict.FAIL);
        BigDecimal passRate = repositoryRuns.isEmpty()
                ? null
                : BigDecimal.valueOf((passed + warned) * 100L)
                        .divide(BigDecimal.valueOf(repositoryRuns.size()), 1, RoundingMode.HALF_UP);

        Run latest = repositoryRuns.isEmpty() ? null : repositoryRuns.getLast();
        List<ReportResponse.MetricRow> metrics = latest == null
                ? List.of()
                : metricRows(repositoryRuns, latest, measurementsByRun);
        return new ReportResponse.RepositoryReport(repository.getId(), repository.fullName(),
                repository.getDefaultBranch(), repositoryRuns.size(), passed, warned, failed, passRate,
                latest == null ? null : new ReportResponse.LatestRun(latest.getId(), latest.getMeasuredAt(),
                        latest.getCommitSha(), latest.getVerdict()),
                metrics);
    }

    /** 最新の Run の指標ごとに、同じコンポーネント・計測条件の期間内で最初の値と比べる。 */
    private static List<ReportResponse.MetricRow> metricRows(List<Run> repositoryRuns, Run latest,
                                                             Map<UUID, List<Measurement>> measurementsByRun) {
        Map<String, BigDecimal> firstValues = new LinkedHashMap<>();
        for (Run run : repositoryRuns) {
            for (Measurement m : measurementsByRun.getOrDefault(run.getId(), List.of())) {
                if (m.getValue() != null) {
                    firstValues.putIfAbsent(keyOf(m), m.getValue());
                }
            }
        }
        return measurementsByRun.getOrDefault(latest.getId(), List.of()).stream()
                .sorted(MEASUREMENT_ORDER)
                .map(m -> {
                    BigDecimal first = firstValues.get(keyOf(m));
                    BigDecimal change = first != null && m.getValue() != null ? m.getValue().subtract(first) : null;
                    return new ReportResponse.MetricRow(m.getMetricId(), MetricCatalog.of(m.getMetricId()).name(),
                            m.getComponentName(), m.getVariant(), m.getStatus(), m.getValue(), m.getUnit(),
                            first, change);
                })
                .toList();
    }

    private static String keyOf(Measurement m) {
        return m.getMetricId() + "|" + Objects.toString(m.getComponentName(), "") + "|"
                + Objects.toString(m.getVariant(), "") + "|" + Objects.toString(m.getScenario(), "");
    }

    private static int count(List<Run> repositoryRuns, Verdict verdict) {
        return (int) repositoryRuns.stream().filter(run -> run.getVerdict() == verdict).count();
    }

    /** 指定が無ければ有効なリポジトリすべて。指定されたものが無ければ 404。 */
    private List<MonitoredRepository> targets(List<UUID> repositoryIds) {
        if (repositoryIds == null || repositoryIds.isEmpty()) {
            return repositories.findByEnabledTrueOrderByOwnerAscNameAsc();
        }
        List<MonitoredRepository> found = repositories.findAllById(repositoryIds).stream()
                .sorted(Comparator.comparing(MonitoredRepository::fullName))
                .toList();
        if (found.size() != Set.copyOf(repositoryIds).size()) {
            throw ApiException.notFound("リポジトリ", repositoryIds.stream().map(UUID::toString)
                    .filter(id -> found.stream().noneMatch(r -> r.getId().toString().equals(id)))
                    .collect(Collectors.joining(", ")));
        }
        return found;
    }

    private Map<UUID, List<Run>> defaultBranchRuns(List<MonitoredRepository> targets, Period period) {
        if (targets.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> defaultBranches = targets.stream()
                .collect(Collectors.toMap(MonitoredRepository::getId, MonitoredRepository::getDefaultBranch));
        return runs.findEvaluatedBetween(defaultBranches.keySet(), period.start(), period.end()).stream()
                .filter(run -> run.getBranch().equals(defaultBranches.get(run.getRepositoryId())))
                .filter(run -> run.getPullRequestNumber() == null)
                .collect(Collectors.groupingBy(Run::getRepositoryId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<UUID, List<Measurement>> measurementsOf(Collection<Run> reportRuns) {
        if (reportRuns.isEmpty()) {
            return Map.of();
        }
        return measurements.findByRunIdIn(reportRuns.stream().map(Run::getId).toList()).stream()
                .collect(Collectors.groupingBy(Measurement::getRunId));
    }

    private static void writeRow(Writer out, List<String> values) throws IOException {
        List<String> cells = new ArrayList<>(values.size());
        for (String value : values) {
            cells.add(csv(value));
        }
        out.write(String.join(",", cells));
        out.write("\r\n");
    }

    /**
     * CSV の 1 セル。カンマ・引用符・改行を含むなら二重引用符で囲む。
     * 表計算ソフトで数式として解釈される先頭文字（= + - @）は、先頭に ' を付けて文字列にする（CSV インジェクション対策）。
     */
    static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = value;
        char first = safe.charAt(0);
        if ((first == '=' || first == '+' || first == '-' || first == '@') && !isNumber(safe)) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static boolean isNumber(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** 期間。{@code start} 以上 {@code end} 未満の計測日時を対象にする。 */
    public record Period(LocalDate from, LocalDate to, Instant start, Instant end) {
    }
}
