package com.qualitygate.job;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.model.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 正規化 → 判定のジョブ。
 *
 * <p><strong>未実装。</strong>アダプタ層（normalize）と判定エンジン（evaluate）は
 * Phase 1 の次ステップで実装する。現時点では Run を FINALIZED のまま残し、
 * 判定済みを装わない。
 */
@Component
public class EvaluateRunJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EvaluateRunJobHandler.class);

    @Override
    public boolean supports(JobType type) {
        return type == JobType.EVALUATE_RUN;
    }

    @Override
    public void handle(Job job) {
        log.info("判定ジョブを受理しました payload={}（判定エンジンは未実装のため何もしません）",
                job.getPayload());
    }
}
