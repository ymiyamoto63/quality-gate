package com.qualitygate.job;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.github.CheckRunPublisher;
import com.qualitygate.github.GitHubApiException;
import com.qualitygate.platform.config.QualityGateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * 判定結果を GitHub の Check Run として出すジョブ（{@code enforcement: check-run} / {@code blocking}）。
 *
 * <p>判定とは別のジョブにする。GitHub の障害で判定が巻き戻らないように（通知と同じ考え方）。
 * GitHub の一時的な失敗は再試行し、App が設定されていない・インストールされていないなど、
 * 再試行しても直らないものは入力の誤りとして記録する。
 */
@Component
public class PublishCheckRunJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PublishCheckRunJobHandler.class);

    private final RunRepository runs;
    private final MonitoredRepositoryRepository repositories;
    private final MeasurementRepository measurements;
    private final CheckRunPublisher publisher;
    private final QualityGateProperties properties;
    private final ObjectMapper objectMapper;

    public PublishCheckRunJobHandler(RunRepository runs, MonitoredRepositoryRepository repositories,
                                     MeasurementRepository measurements, CheckRunPublisher publisher,
                                     QualityGateProperties properties, ObjectMapper objectMapper) {
        this.runs = runs;
        this.repositories = repositories;
        this.measurements = measurements;
        this.publisher = publisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.PUBLISH_CHECK_RUN;
    }

    @Override
    public void handle(Job job) {
        JsonNode payload = objectMapper.readTree(job.getPayload());
        UUID runId = UUID.fromString(payload.path("runId").asString());
        String enforcement = payload.path("enforcement").asString("check-run");
        if (!publisher.available()) {
            throw new JobInputException("Check Run は GitHub App でしか作れません。"
                    + "QG_GITHUB_APP_ID と QG_GITHUB_APP_PRIVATE_KEY を設定してください runId=" + runId);
        }
        Run run = runs.findById(runId).orElseThrow(() -> new JobInputException("Run が存在しません: " + runId));
        if (run.getStatus() != RunStatus.EVALUATED) {
            throw new JobInputException("判定が終わっていない Run です runId=%s status=%s"
                    .formatted(runId, run.getStatus()));
        }
        MonitoredRepository repository = repositories.findById(run.getRepositoryId())
                .orElseThrow(() -> new JobInputException("リポジトリが存在しません: " + run.getRepositoryId()));
        String detailUrl = "%s/runs/%s".formatted(properties.baseUrl().replaceAll("/+$", ""), runId);
        try {
            long checkRunId = publisher.publish(repository, run, measurements.findByRunId(runId), enforcement,
                    detailUrl);
            log.info("Check Run を出しました runId={} checkRunId={} verdict={} enforcement={}",
                    runId, checkRunId, run.getVerdict(), enforcement);
        } catch (GitHubApiException e) {
            // 接続の失敗やエラー応答は一時的なことが多い。試行回数の上限までは再試行する
            throw new RetryableJobException("Check Run を出せませんでした: " + e.getMessage(), e);
        }
    }
}
