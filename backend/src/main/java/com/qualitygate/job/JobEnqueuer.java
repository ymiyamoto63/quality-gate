package com.qualitygate.job;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.model.JobStatus;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.platform.id.Uuid7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ジョブの登録。同じ鍵のジョブが実行待ち・実行中なら重複して積まない。
 *
 * <p>呼び出し元のトランザクションに参加する。業務の変更（再評価の依頼の監査ログなど）と
 * ジョブの登録が同時に確定し、片方だけが残ることがない。
 */
@Service
public class JobEnqueuer {

    private final JobRepository jobs;
    private final ObjectMapper objectMapper;

    public JobEnqueuer(JobRepository jobs, ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.objectMapper = objectMapper;
    }

    /** @return 登録した、または既に積まれていたジョブの ID */
    @Transactional
    public UUID enqueue(JobType type, String dedupKey, Map<String, ?> payload) {
        UUID id = Uuid7.generate();
        int inserted = jobs.insertIfAbsent(id, type.name(), dedupKey,
                objectMapper.writeValueAsString(payload), Instant.now());
        if (inserted == 1 || dedupKey == null) {
            return id;
        }
        return jobs.findFirstByTypeAndDedupKeyAndStatusIn(type, dedupKey,
                        List.of(JobStatus.PENDING, JobStatus.RUNNING))
                .map(Job::getId)
                .orElse(id);
    }

    /** Run の再評価を積む。鍵は runId（同じ Run の再評価は 1 つにまとめる）。 */
    @Transactional
    public UUID enqueueReevaluation(UUID runId, String trigger) {
        return enqueue(JobType.REEVALUATE_RUN, runId.toString(),
                Map.of("runId", runId.toString(), "trigger", trigger));
    }
}
