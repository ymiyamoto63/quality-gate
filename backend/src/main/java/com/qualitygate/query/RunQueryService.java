package com.qualitygate.query;

import com.qualitygate.domain.entity.Finding;
import com.qualitygate.domain.entity.GateConfig;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.RunSkippedMetric;
import com.qualitygate.domain.entity.Waiver;
import com.qualitygate.domain.repo.WaiverRepository;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.MetricCategory;
import com.qualitygate.domain.metric.MetricDefinition;
import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.FindingCriteria;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.web.PageCursor;
import com.qualitygate.query.dto.FindingListResponse;
import com.qualitygate.query.dto.RunDetailResponse;
import com.qualitygate.query.dto.RunListResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Run 詳細と違反一覧の読み取りモデル。
 *
 * <p>判定はしない。表示に必要な形へ組み替えるだけであり、ステータスや理由は
 * すべて判定時に確定した値をそのまま返す。参照時に判定し直すと、同じ Run が
 * 見るたびに違う結果になりうる。
 */
@Service
public class RunQueryService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /** 1 ページの既定と上限（docs/initial/07-api-design.md 1.4）。 */
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final RunRepository runs;
    private final MonitoredRepositoryRepository repositories;
    private final MeasurementRepository measurements;
    private final FindingRepository findings;
    private final RunSkippedMetricRepository skippedMetrics;
    private final ArtifactRecordRepository artifacts;
    private final GateConfigRepository gateConfigs;
    private final WaiverRepository waivers;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("java:S107")
    public RunQueryService(RunRepository runs, MonitoredRepositoryRepository repositories,
                           MeasurementRepository measurements, FindingRepository findings,
                           RunSkippedMetricRepository skippedMetrics,
                           ArtifactRecordRepository artifacts, GateConfigRepository gateConfigs,
                           WaiverRepository waivers, ObjectMapper objectMapper) {
        this.waivers = waivers;
        this.runs = runs;
        this.repositories = repositories;
        this.measurements = measurements;
        this.findings = findings;
        this.skippedMetrics = skippedMetrics;
        this.artifacts = artifacts;
        this.gateConfigs = gateConfigs;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RunDetailResponse detail(UUID runId) {
        Run run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Run", runId));
        String fullName = repositories.findById(run.getRepositoryId())
                .map(MonitoredRepository::fullName).orElse(null);

        Map<String, Long> findingCounts = countsByMetric(runId);

        return new RunDetailResponse(
                run.getId(),
                new RunDetailResponse.RepositoryRef(run.getRepositoryId(), fullName),
                run.getCommitSha(),
                SourceLinks.commit(fullName, run.getCommitSha()),
                run.getBaseCommitSha(),
                run.getBaselineRunId(),
                run.getBranch(),
                run.getPullRequestNumber(),
                run.getAttempt(),
                run.getRunnerType(),
                run.getStatus(),
                run.getVerdict(),
                run.getCompleteness(),
                run.getMeasuredAt(),
                run.getEvaluatedAt(),
                run.getCiRunUrl(),
                failureOf(run),
                gateConfigOf(run),
                categoriesOf(runId, findingCounts),
                findingSummaryOf(runId),
                skippedMetricsOf(runId),
                artifacts.findByRunId(runId).size());
    }

    @Transactional(readOnly = true)
    public FindingListResponse findings(UUID runId, FindingCriteria criteria, int limit,
                                        String cursor) {
        Run run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Run", runId));
        String fullName = repositories.findById(run.getRepositoryId())
                .map(MonitoredRepository::fullName).orElse(null);

        int offset = cursor == null ? 0 : PageCursor.toOffset(cursor);
        int pageSize = normalizeLimit(limit);

        // 1 件多く取り、次ページの有無を追加のクエリなしで判断する
        List<Finding> page = findings.search(criteria, offset, pageSize + 1);
        boolean hasMore = page.size() > pageSize;
        List<Finding> items = hasMore ? page.subList(0, pageSize) : page;

        Map<UUID, Waiver> waiverById = new HashMap<>();
        waivers.findAllById(items.stream().map(Finding::getWaiverId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(w -> waiverById.put(w.getId(), w));

        return new FindingListResponse(
                items.stream().map(f -> toItem(f, fullName, run.getCommitSha(),
                        waiverById.get(f.getWaiverId()))).toList(),
                hasMore ? PageCursor.ofOffset(offset + pageSize) : null,
                hasMore,
                findings.count(criteria),
                run.getRepositoryId());
    }

    @Transactional(readOnly = true)
    public RunListResponse list(UUID repositoryId, int limit, String cursor) {
        int pageSize = normalizeLimit(limit);
        PageRequest request = PageRequest.of(0, pageSize + 1);
        List<Run> page;
        if (cursor == null) {
            page = runs.findByRepositoryIdOrderByMeasuredAtDesc(repositoryId, request);
        } else {
            PageCursor.Keyset keyset = PageCursor.toKeyset(cursor);
            page = runs.findPageAfter(repositoryId, keyset.measuredAt(), keyset.id(), request);
        }

        boolean hasMore = page.size() > pageSize;
        List<Run> items = hasMore ? page.subList(0, pageSize) : page;
        Run last = items.isEmpty() ? null : items.get(items.size() - 1);

        return new RunListResponse(
                items.stream().map(RunQueryService::toListItem).toList(),
                hasMore && last != null
                        ? PageCursor.ofKeyset(last.getMeasuredAt(), last.getId()) : null,
                hasMore);
    }

    static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static RunListResponse.RunSummary toListItem(Run run) {
        return new RunListResponse.RunSummary(run.getId(), run.getCommitSha(), run.getBranch(),
                run.getPullRequestNumber(), run.getRunnerType(), run.getStatus(),
                run.getVerdict(), run.getCompleteness(), run.getMeasuredAt(),
                run.getEvaluatedAt());
    }

    /**
     * 指標をカテゴリごとにまとめる。
     *
     * <p>判定結果が 1 件も無いカテゴリは返さない。判定していないカテゴリを
     * 「合格」として並べると、測っていないことが見えなくなる。
     */
    private List<RunDetailResponse.RunCategory> categoriesOf(UUID runId,
                                                          Map<String, Long> findingCounts) {
        Map<MetricCategory, List<RunDetailResponse.RunMetric>> grouped =
                new EnumMap<>(MetricCategory.class);

        List<Measurement> rows = new ArrayList<>(measurements.findByRunId(runId));
        rows.sort(Comparator
                .comparing(Measurement::getMetricId, MetricCatalog::compareByCatalogOrder)
                .thenComparing(m -> m.getComponentName() == null ? "" : m.getComponentName())
                .thenComparing(m -> m.getVariant() == null ? "" : m.getVariant()));

        for (Measurement measurement : rows) {
            MetricDefinition definition = MetricCatalog.of(measurement.getMetricId());
            grouped.computeIfAbsent(definition.category(), key -> new ArrayList<>())
                    .add(toMetric(measurement, definition, findingCounts));
        }

        List<RunDetailResponse.RunCategory> categories = new ArrayList<>();
        grouped.forEach((category, metrics) -> {
            MeasurementStatus worst = metrics.stream()
                    .map(RunDetailResponse.RunMetric::status)
                    .max(Comparator.comparingInt(RunQueryService::severityRank))
                    .orElse(MeasurementStatus.SKIP);
            categories.add(new RunDetailResponse.RunCategory(category.displayName(), worst,
                    expandByDefault(worst), metrics));
        });
        return categories;
    }

    /**
     * 初期状態で展開するか。
     *
     * <p>不合格・注意・計測エラーを含むカテゴリだけ開く。全部たたむと必ず探す操作が
     * 入り、全部開くと問題が他に埋もれる（docs/initial/08-screen-design.md 4.3）。
     */
    private static boolean expandByDefault(MeasurementStatus worst) {
        return worst == MeasurementStatus.FAIL
                || worst == MeasurementStatus.WARN
                || worst == MeasurementStatus.ERROR;
    }

    private RunDetailResponse.RunMetric toMetric(Measurement measurement,
                                              MetricDefinition definition,
                                              Map<String, Long> findingCounts) {
        BigDecimal delta = deltaOf(measurement);
        return new RunDetailResponse.RunMetric(
                measurement.getMetricId(),
                definition.name(),
                measurement.getComponentName(),
                measurement.getVariant(),
                MetricCatalog.variantLabel(measurement.getMetricId(), measurement.getVariant()),
                measurement.getStatus(),
                measurement.getValue(),
                measurement.getUnit(),
                toMap(measurement.getThreshold()),
                measurement.getPreviousValue(),
                delta,
                improvedOf(delta, definition),
                measurement.getReason(),
                toMap(measurement.getDetail()),
                findingCounts.getOrDefault(measurement.getMetricId(), 0L));
    }

    /** 前回値か今回値のどちらかが無ければ差分は出さない。0 との差として扱わない。 */
    private static BigDecimal deltaOf(Measurement measurement) {
        if (measurement.getValue() == null || measurement.getPreviousValue() == null) {
            return null;
        }
        return measurement.getValue().subtract(measurement.getPreviousValue());
    }

    /**
     * 差分が良い方向かどうか。
     *
     * <p>指標によって向きが逆（カバレッジは増えるほど良い、脆弱性件数は減るほど良い）で、
     * 画面側で符号を見て判断させると指標ごとの向きの定義が二重化する。
     */
    private static Boolean improvedOf(BigDecimal delta, MetricDefinition definition) {
        if (delta == null || delta.signum() == 0) {
            return null;
        }
        return definition.higherIsBetter() == (delta.signum() > 0);
    }

    private static int severityRank(MeasurementStatus status) {
        return switch (status) {
            case ERROR -> 5;
            case FAIL -> 4;
            case WARN -> 3;
            case REFERENCE -> 2;
            case SKIP -> 1;
            case PASS -> 0;
            case NOT_APPLICABLE -> -1;
        };
    }

    /** 未解消の違反だけを数える。解消済みを含めると「まだ N 件ある」が誤りになる。 */
    private Map<String, Long> countsByMetric(UUID runId) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : findings.countByMetric(runId)) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    private RunDetailResponse.RunFindingSummary findingSummaryOf(UUID runId) {
        Map<FindingState, Long> byState = new EnumMap<>(FindingState.class);
        for (Object[] row : findings.countByState(runId)) {
            byState.put((FindingState) row[0], (Long) row[1]);
        }
        return new RunDetailResponse.RunFindingSummary(
                byState.getOrDefault(FindingState.NEW, 0L),
                byState.getOrDefault(FindingState.CONTINUING, 0L),
                byState.getOrDefault(FindingState.RESOLVED, 0L),
                byState.getOrDefault(FindingState.INITIAL, 0L),
                findings.countByRunIdAndWaiverIdIsNotNull(runId));
    }

    private List<RunDetailResponse.SkippedMetricView> skippedMetricsOf(UUID runId) {
        List<RunSkippedMetric> declared = new ArrayList<>(skippedMetrics.findByKeyRunId(runId));
        declared.sort(Comparator.comparing(RunSkippedMetric::getMetricId,
                MetricCatalog::compareByCatalogOrder));
        return declared.stream()
                .map(skip -> new RunDetailResponse.SkippedMetricView(skip.getMetricId(),
                        MetricCatalog.of(skip.getMetricId()).name(),
                        skip.getReason(), skip.isAccepted()))
                .toList();
    }

    private RunDetailResponse.GateConfigRef gateConfigOf(Run run) {
        if (run.getGateConfigId() == null) {
            return null;
        }
        Optional<GateConfig> config = gateConfigs.findById(run.getGateConfigId());
        return new RunDetailResponse.GateConfigRef(run.getGateConfigId(),
                config.map(GateConfig::getVersion).orElse(null),
                config.map(GateConfig::getSourceType).orElse(null),
                config.map(GateConfig::getSourceCommitSha).orElse(null));
    }

    /**
     * 処理失敗の内容。
     *
     * <p>{@code hint} を返すのは、失敗を見た人がその場で次の行動を決められるように
     * するため。エラーコードだけでは、CI の設定を直すのか再実行すればよいのか判らない。
     */
    private static RunDetailResponse.RunFailure failureOf(Run run) {
        if (run.getStatus() != RunStatus.FAILED) {
            return null;
        }
        String code = run.getErrorCode();
        return new RunDetailResponse.RunFailure(code, titleOf(code), run.getErrorDetail(), hintOf(code));
    }

    private static String titleOf(String errorCode) {
        if (errorCode == null) {
            return "処理に失敗しました";
        }
        return switch (errorCode) {
            case "CONFIG_VALIDATION_FAILED" -> "設定ファイルの内容が不正です";
            case "ARTIFACT_FORMAT_INVALID" -> "成果物の形式が不正です";
            case "ARTIFACTS_DELETED" -> "成果物が保持期間を過ぎて削除されています";
            default -> "処理に失敗しました";
        };
    }

    private static String hintOf(String errorCode) {
        if (errorCode == null) {
            return "再評価を試し、解消しない場合は管理者に連絡してください。";
        }
        return switch (errorCode) {
            case "CONFIG_VALIDATION_FAILED" ->
                    ".quality-gate.yml を修正して CI を再実行してください。"
                            + "示された行番号とキー名がそのまま修正箇所です。";
            case "ARTIFACT_FORMAT_INVALID" ->
                    "CI が出力した成果物が期待する形式か確認してください。"
                            + "形式が不正な場合は再実行しても直りません。";
            case "ARTIFACTS_DELETED" ->
                    "この Run の成果物は保持期間を過ぎています。CI を再実行して計測し直してください。";
            default -> "再評価を試し、解消しない場合は管理者に連絡してください。";
        };
    }

    private FindingListResponse.FindingItem toItem(Finding finding, String fullName, String commitSha,
                                                   Waiver waiver) {
        return new FindingListResponse.FindingItem(
                finding.getId(),
                finding.getMetricId(),
                MetricCatalog.of(finding.getMetricId()).name(),
                finding.getFingerprint(),
                finding.getState(),
                finding.getSeverity(),
                finding.getRuleId(),
                finding.getTitle(),
                finding.getFilePath(),
                finding.getLine(),
                finding.getComponentName(),
                SourceLinks.blob(fullName, commitSha, finding.getFilePath(), finding.getLine()),
                toMap(finding.getDetail()),
                waiver == null ? null : new FindingListResponse.Waiver(waiver.getId(),
                        waiver.getExpiresAt().atOffset(java.time.ZoneOffset.UTC).toLocalDate(),
                        waiver.getReason(), waiver.getReasonCategory(), waiver.getStatus()));
    }

    /** jsonb 列を JSON のオブジェクトとして返す。文字列のまま返すと画面側で再パースになる。 */
    private Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return new LinkedHashMap<>(objectMapper.readValue(json, JSON_OBJECT));
    }
}
