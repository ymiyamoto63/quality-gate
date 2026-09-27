package com.qualitygate.pipeline;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.evaluate.GateThresholds;
import com.qualitygate.evaluate.RunEvaluationService;
import com.qualitygate.normalize.ReportNormalizer;
import com.qualitygate.platform.config.QualityGateProperties;
import com.qualitygate.platform.observability.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 正規化 → 判定を、呼び出したスレッドでその場で行う（取り込みの確定から呼ぶ。DD-15）。
 *
 * <p>合格ラインは環境変数（{@link QualityGateProperties.Gate}）から、判定のたびに作る。
 *
 * <p>パースはトランザクションの外で行い、判定と保存だけを 1 トランザクションにまとめる。
 * 途中で失敗した Run に中途半端な判定結果が残らないようにするため。
 *
 * <p>判定に失敗しても例外は投げず、Run を「処理失敗」（{@code FAILED}）として記録して返す。
 * 失敗の理由は収集ランナーのログ（finalize の応答）とサーバのログに出る。原因を直したら計測し直す。
 */
@Component
public class RunEvaluationPipeline {

    private static final Logger log = LoggerFactory.getLogger(RunEvaluationPipeline.class);

    /** 想定外の例外で判定できなかった Run のエラーコード。 */
    static final String EVALUATION_FAILED = "EVALUATION_FAILED";

    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final QualityGateProperties properties;
    private final ReportNormalizer normalizer;
    private final RunEvaluationService evaluationService;

    public RunEvaluationPipeline(RunRepository runs, ArtifactRecordRepository artifacts,
                                 QualityGateProperties properties,
                                 ReportNormalizer normalizer,
                                 RunEvaluationService evaluationService) {
        this.runs = runs;
        this.artifacts = artifacts;
        this.properties = properties;
        this.normalizer = normalizer;
        this.evaluationService = evaluationService;
    }

    /** @return 判定後の Run（判定済み、または処理失敗） */
    public Run evaluate(UUID runId) {
        MDC.put(CorrelationIds.RUN_ID, runId.toString());
        try {
            evaluateOrThrow(runId);
        } catch (RuntimeException e) {
            log.error("判定に失敗しました runId={}", runId, e);
            markFailed(runId, EVALUATION_FAILED, e.getMessage());
        } finally {
            MDC.remove(CorrelationIds.RUN_ID);
        }
        return runs.findById(runId).orElseThrow();
    }

    private void evaluateOrThrow(UUID runId) {
        List<ArtifactRecord> records = artifacts.findByRunId(runId);

        GateThresholds thresholds = GateThresholds.from(properties.gate());

        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions());

        log.info("正規化が完了しました runId={} 成果物={}件 指標={} 違反={}件 解析失敗={}",
                runId, records.size(), input.metricsWithData(),
                input.findings().size(), input.parseErrors().keySet());

        evaluationService.evaluate(runId, input, thresholds);
    }

    /** 判定のトランザクションはロールバック済み。処理失敗の記録だけを別に保存する。 */
    private void markFailed(UUID runId, String errorCode, String detail) {
        runs.findById(runId).ifPresent(run -> {
            run.markFailed(errorCode, detail == null ? "" : detail);
            runs.save(run);
        });
    }
}
