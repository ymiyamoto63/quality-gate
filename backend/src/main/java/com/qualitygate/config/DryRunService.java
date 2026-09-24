package com.qualitygate.config;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.gate.GateConfigDocument;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.evaluate.GateThresholds;
import com.qualitygate.evaluate.MetricResult;
import com.qualitygate.evaluate.RunEvaluationService;
import com.qualitygate.normalize.ReportNormalizer;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 設定変更のドライラン（FR-02-5）。直近の Run を、保存前の設定で判定し直した場合の結果を試算する。
 *
 * <p>判定結果は保存しない（Run・判定・サマリ・通知は変わらない）。対象は既定ブランチで判定された直近の Run で、
 * 実際の再評価と同じく、保存されている成果物から正規化し直して判定する。成果物が保持期間で削除されている Run は試算できない。
 */
@Service
public class DryRunService {

    private static final int DEFAULT_RUNS = 10;

    /** 既定ブランチ以外の Run が多くても対象を集められるよう、多めに読んでから絞る。 */
    private static final int SCAN_LIMIT = 300;

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final MeasurementRepository measurements;
    private final GateConfigParser parser;
    private final ReportNormalizer normalizer;
    private final RunEvaluationService evaluationService;

    public DryRunService(MonitoredRepositoryRepository repositories, RunRepository runs,
                         ArtifactRecordRepository artifacts, MeasurementRepository measurements,
                         GateConfigParser parser, ReportNormalizer normalizer,
                         RunEvaluationService evaluationService) {
        this.repositories = repositories;
        this.runs = runs;
        this.artifacts = artifacts;
        this.measurements = measurements;
        this.parser = parser;
        this.normalizer = normalizer;
        this.evaluationService = evaluationService;
    }

    public DryRunResponse dryRun(UUID repositoryId, String rawYaml, Integer runCount) {
        MonitoredRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> ApiException.notFound("リポジトリ", repositoryId));
        GateThresholds thresholds = GateThresholds.from(parse(rawYaml));
        int limit = runCount == null ? DEFAULT_RUNS : runCount;

        List<Run> targets = runs.findByRepositoryIdOrderByMeasuredAtDesc(repositoryId, PageRequest.of(0, SCAN_LIMIT))
                .stream()
                .filter(run -> run.getStatus() == RunStatus.EVALUATED && run.getVerdict() != null)
                .filter(run -> repository.getDefaultBranch().equals(run.getBranch()))
                .filter(run -> run.getPullRequestNumber() == null)
                .limit(limit)
                .toList();

        List<DryRunResponse.RunResult> results = new ArrayList<>();
        List<DryRunResponse.SkippedRun> skipped = new ArrayList<>();
        for (Run run : targets) {
            List<ArtifactRecord> records = artifacts.findByRunId(run.getId());
            if (records.stream().anyMatch(a -> a.getDeletedAt() != null)) {
                skipped.add(new DryRunResponse.SkippedRun(run.getId(),
                        "成果物が保持期間を過ぎて削除されているため試算できません"));
                continue;
            }
            RunEvaluationService.Simulation simulation = evaluationService.simulate(run.getId(),
                    normalizer.normalize(records, thresholds.exclusions()), thresholds);
            results.add(new DryRunResponse.RunResult(run.getId(), run.getMeasuredAt(), run.getCommitSha(),
                    run.getVerdict(), simulation.verdict(),
                    changesOf(measurements.findByRunId(run.getId()), simulation.results())));
        }

        int verdictChanged = (int) results.stream()
                .filter(r -> r.currentVerdict() != r.simulatedVerdict()).count();
        int newlyFailing = (int) results.stream()
                .filter(r -> r.currentVerdict() != Verdict.FAIL && r.simulatedVerdict() == Verdict.FAIL).count();
        int newlyPassing = (int) results.stream()
                .filter(r -> r.currentVerdict() == Verdict.FAIL && r.simulatedVerdict() != Verdict.FAIL).count();
        return new DryRunResponse(results.size(), verdictChanged, newlyFailing, newlyPassing, results, skipped);
    }

    private GateConfigDocument parse(String rawYaml) {
        try {
            return parser.parse(rawYaml);
        } catch (ConfigValidationException e) {
            throw new ApiException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "設定に %d 件の誤りがあります".formatted(e.errors().size()),
                    Map.of("errors", e.errors().stream().map(ConfigQueryService::errorOf).toList()));
        }
    }

    /** 状態が変わる指標（新しく判定される・判定されなくなるものを含む）。 */
    private static List<DryRunResponse.MetricChange> changesOf(List<Measurement> current, List<MetricResult> simulated) {
        Map<String, MeasurementStatus> before = new HashMap<>();
        current.forEach(m -> before.put(keyOf(m.getMetricId(), m.getComponentName(), m.getVariant()), m.getStatus()));
        List<DryRunResponse.MetricChange> changes = new ArrayList<>();
        for (MetricResult result : simulated) {
            String key = keyOf(result.metricId(), result.componentName(), result.variant());
            MeasurementStatus was = before.remove(key);
            if (was != result.status()) {
                changes.add(new DryRunResponse.MetricChange(result.metricId(), result.componentName(), was,
                        result.status(), result.value(), result.unit(), result.reason()));
            }
        }
        // 新しい設定では判定しなくなる（無効にした）指標
        before.forEach((key, status) -> {
            String[] parts = key.split("\\|", -1);
            changes.add(new DryRunResponse.MetricChange(parts[0], parts[1].isEmpty() ? null : parts[1], status,
                    MeasurementStatus.NOT_APPLICABLE, null, null, "新しい設定では判定しません（無効）"));
        });
        changes.sort((a, b) -> (a.metricId() + Objects.toString(a.componentName(), ""))
                .compareTo(b.metricId() + Objects.toString(b.componentName(), "")));
        return changes;
    }

    private static String keyOf(String metricId, String componentName, String variant) {
        return metricId + "|" + Objects.toString(componentName, "") + "|" + Objects.toString(variant, "");
    }
}
