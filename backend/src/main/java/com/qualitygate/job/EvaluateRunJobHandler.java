package com.qualitygate.job;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.evaluate.GateThresholds;
import com.qualitygate.evaluate.RunEvaluationService;
import com.qualitygate.normalize.ReportNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * 正規化 → 判定のジョブ。
 *
 * <p>パースはトランザクションの外で行い、判定と保存だけを 1 トランザクションにまとめる。
 * 途中で失敗した Run に中途半端な判定結果が残らないようにするため。
 */
@Component
public class EvaluateRunJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EvaluateRunJobHandler.class);

    /**
     * 計測除外の既定値。
     *
     * <p>本来はリポジトリの {@code .quality-gate.yml} の {@code exclusions} を使う。
     * 設定解決の実装が入るまでの既定値。
     */
    private static final List<String> DEFAULT_EXCLUSIONS = List.of(
            "**/generated/**", "**/*.config.ts", "**/schema.d.ts");

    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final ReportNormalizer normalizer;
    private final RunEvaluationService evaluationService;
    private final ObjectMapper objectMapper;

    public EvaluateRunJobHandler(RunRepository runs, ArtifactRecordRepository artifacts,
                                 ReportNormalizer normalizer,
                                 RunEvaluationService evaluationService,
                                 ObjectMapper objectMapper) {
        this.runs = runs;
        this.artifacts = artifacts;
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
        if (runs.findById(runId).isEmpty()) {
            // Run が保持期間で削除されている。再実行しても回復しない。
            throw new IllegalStateException("Run が存在しません: " + runId);
        }

        List<ArtifactRecord> records = artifacts.findByRunId(runId);
        NormalizedInput input = normalizer.normalize(records, DEFAULT_EXCLUSIONS);

        log.info("正規化が完了しました runId={} 成果物={}件 指標={} 違反={}件 解析失敗={}",
                runId, records.size(), input.metricsWithData(),
                input.headFindings().size(), input.parseErrors().keySet());

        evaluationService.evaluate(runId, input, GateThresholds.defaults());
    }

    private UUID runIdOf(Job job) {
        String runId = objectMapper.readTree(job.getPayload()).path("runId").asString(null);
        if (runId == null) {
            throw new IllegalStateException("ジョブの payload に runId がありません: " + job.getPayload());
        }
        return UUID.fromString(runId);
    }
}
