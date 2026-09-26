package com.qualitygate.pipeline;

import com.qualitygate.config.GateConfigService;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.evaluate.GateThresholds;
import com.qualitygate.evaluate.RunEvaluationService;
import com.qualitygate.normalize.RenameHistory;
import com.qualitygate.normalize.ReportNormalizer;
import com.qualitygate.platform.observability.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 設定解決 → 正規化 → 判定を、呼び出したスレッドでその場で行う（取り込みの確定と再評価から呼ぶ。DD-15）。
 *
 * <p>パースはトランザクションの外で行い、判定と保存だけを 1 トランザクションにまとめる。
 * 途中で失敗した Run に中途半端な判定結果が残らないようにするため。
 *
 * <p>判定に失敗しても例外は投げず、Run を「処理失敗」（{@code FAILED}）として記録して返す。
 * 失敗の理由は Run 詳細に表示され、管理者は原因を直した後に再評価できる。
 */
@Component
public class RunEvaluationPipeline {

    private static final Logger log = LoggerFactory.getLogger(RunEvaluationPipeline.class);

    /** 想定外の例外で判定できなかった Run のエラーコード。 */
    static final String EVALUATION_FAILED = "EVALUATION_FAILED";

    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final GateConfigService gateConfigService;
    private final ReportNormalizer normalizer;
    private final RunEvaluationService evaluationService;
    private final ObjectMapper objectMapper;
    private final RenameHistory renameHistory;

    public RunEvaluationPipeline(RunRepository runs, ArtifactRecordRepository artifacts,
                                 GateConfigService gateConfigService,
                                 ReportNormalizer normalizer,
                                 RunEvaluationService evaluationService,
                                 ObjectMapper objectMapper,
                                 RenameHistory renameHistory) {
        this.runs = runs;
        this.artifacts = artifacts;
        this.gateConfigService = gateConfigService;
        this.normalizer = normalizer;
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
        this.renameHistory = renameHistory;
    }

    /** @return 判定後の Run（判定済み、または処理失敗） */
    public Run evaluate(UUID runId) {
        MDC.put(CorrelationIds.RUN_ID, runId.toString());
        try {
            evaluateOrThrow(runId);
        } catch (ConfigValidationException e) {
            // 不正な設定で判定を続けると、意図しないしきい値で合格が出てしまう。
            // 判定結果 FAIL ではなく「処理失敗」として記録し、両者を混同させない。
            log.warn("設定の検証に失敗しました runId={} reason={}", runId, e.getMessage());
            markFailed(runId, "CONFIG_VALIDATION_FAILED", detailOf(e));
        } catch (RuntimeException e) {
            log.error("判定に失敗しました runId={}", runId, e);
            markFailed(runId, EVALUATION_FAILED, e.getMessage());
        } finally {
            MDC.remove(CorrelationIds.RUN_ID);
        }
        return runs.findById(runId).orElseThrow();
    }

    private void evaluateOrThrow(UUID runId) {
        Run run = runs.findById(runId).orElseThrow(
                () -> new IllegalStateException("Run が存在しません: " + runId));
        List<ArtifactRecord> records = artifacts.findByRunId(runId);

        GateConfigService.Resolved config = gateConfigService.resolve(run, records);
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

    /** 判定のトランザクションはロールバック済み。処理失敗の記録だけを別に保存する。 */
    private void markFailed(UUID runId, String errorCode, String detail) {
        runs.findById(runId).ifPresent(run -> {
            run.markFailed(errorCode, detail == null ? "" : detail);
            runs.save(run);
        });
    }

    private static String detailOf(ConfigValidationException e) {
        return e.errors().stream()
                .map(error -> error.line() == null
                        ? "%s: %s".formatted(error.path(), error.message())
                        : "%d 行目 %s: %s".formatted(error.line(), error.path(), error.message()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse(e.getMessage());
    }
}
