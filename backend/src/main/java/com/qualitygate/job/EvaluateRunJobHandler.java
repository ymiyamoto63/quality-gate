package com.qualitygate.job;

import com.qualitygate.config.GateConfigService;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.evaluate.GateThresholds;
import com.qualitygate.evaluate.RunEvaluationService;
import com.qualitygate.normalize.RenameHistory;
import com.qualitygate.normalize.ReportNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 設定解決 → 正規化 → 判定のジョブ。
 *
 * <p>パースはトランザクションの外で行い、判定と保存だけを 1 トランザクションにまとめる。
 * 途中で失敗した Run に中途半端な判定結果が残らないようにするため。
 */
@Component
public class EvaluateRunJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EvaluateRunJobHandler.class);

    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final GateConfigService gateConfigService;
    private final ReportNormalizer normalizer;
    private final RunEvaluationService evaluationService;
    private final ObjectMapper objectMapper;
    private final RenameHistory renameHistory;

    public EvaluateRunJobHandler(RunRepository runs, ArtifactRecordRepository artifacts,
                                 GateConfigService gateConfigService,
                                 ReportNormalizer normalizer,
                                 RunEvaluationService evaluationService,
                                 ObjectMapper objectMapper,
                                 RenameHistory renameHistory) {
        this.renameHistory = renameHistory;
        this.runs = runs;
        this.artifacts = artifacts;
        this.gateConfigService = gateConfigService;
        this.normalizer = normalizer;
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.EVALUATE_RUN || type == JobType.REEVALUATE_RUN;
    }

    @Override
    public void handle(Job job) {
        UUID runId = runIdOf(job);
        Run run = runs.findById(runId).orElseThrow(
                // Run が保持期間で削除されている。再実行しても回復しない。
                () -> new IllegalStateException("Run が存在しません: " + runId));

        List<ArtifactRecord> records = artifacts.findByRunId(runId);
        if (job.getType() == JobType.REEVALUATE_RUN
                && records.stream().anyMatch(a -> a.getDeletedAt() != null)) {
            // 実体の無い成果物を読むと全指標が ERROR になり、確定済みの判定を壊してしまう
            throw new JobInputException("成果物が保持期間を過ぎて削除されているため再評価しません runId="
                    + runId);
        }

        GateConfigService.Resolved config;
        try {
            config = gateConfigService.resolve(run, records);
        } catch (ConfigValidationException e) {
            // 不正な設定で判定を続けると、意図しないしきい値で合格が出てしまう。
            // 判定結果 FAIL ではなく「処理失敗」として記録し、両者を混同させない。
            markConfigInvalid(run, e);
            throw new JobInputException("設定の検証に失敗しました: " + e.getMessage(), e);
        }
        GateThresholds thresholds = GateThresholds.from(config.document());

        recordRenames(run, records);
        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions(), normalizer.renamesOf(run));

        log.info("正規化が完了しました runId={} 設定={} 成果物={}件 指標={} 違反={}件 解析失敗={}",
                runId, config.isDefault() ? "既定値" : "v" + config.gateConfig().getVersion(),
                records.size(), input.metricsWithData(),
                input.headFindings().size(), input.parseErrors().keySet());

        evaluationService.evaluate(runId, input, thresholds,
                config.isDefault() ? null : config.gateConfig().getId());
    }

    /**
     * ファイルの移動・リネームの対応表を、収集ランナーが送った移動の記録から求めて Run に保持する（指標仕様書 0.4）。
     *
     * <p>比較元コミット（M-07 の比較元）と比較対象 Run のコミット（違反の新規 / 継続の判定）の両方から求める。
     * 一度求めたら再評価でも同じものを使う（判定を再現できるように）。失敗しても判定は続ける
     */
    private void recordRenames(Run run, List<ArtifactRecord> records) {
        if (run.getRenamedFiles() != null) {
            return;
        }
        Set<String> from = new LinkedHashSet<>();
        if (run.getBaseCommitSha() != null) {
            from.add(run.getBaseCommitSha());
        }
        evaluationService.findBaseline(run).map(Run::getCommitSha).ifPresent(from::add);
        from.remove(run.getCommitSha());
        if (from.isEmpty()) {
            return;
        }
        renameHistory.resolve(records, from).ifPresent(renames -> {
            run.setRenamedFiles(objectMapper.writeValueAsString(renames));
            runs.save(run);
        });
    }

    private void markConfigInvalid(Run run, ConfigValidationException e) {
        String detail = e.errors().stream()
                .map(error -> error.line() == null
                        ? "%s: %s".formatted(error.path(), error.message())
                        : "%d 行目 %s: %s".formatted(error.line(), error.path(), error.message()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse(e.getMessage());
        run.markFailed("CONFIG_VALIDATION_FAILED", detail);
        runs.save(run);
    }

    private UUID runIdOf(Job job) {
        String runId = objectMapper.readTree(job.getPayload()).path("runId").asString(null);
        if (runId == null) {
            throw new IllegalStateException("ジョブの payload に runId がありません: " + job.getPayload());
        }
        return UUID.fromString(runId);
    }
}
