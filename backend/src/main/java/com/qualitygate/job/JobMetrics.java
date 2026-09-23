package com.qualitygate.job;

import com.qualitygate.domain.model.JobStatus;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ジョブとストレージのゲージ（docs/initial/05-architecture.md 10.2 / 10.3）。
 *
 * <ul>
 *   <li>{@code qg.jobs.pending}（{@code type}）: 実行待ちのジョブ数。10 分以上 10 件超ならワーカーの停止を疑う</li>
 *   <li>{@code qg.jobs.dead}: 恒久的に失敗したジョブ数。1 件でも増えたら手動で確認する</li>
 *   <li>{@code qg.artifacts.bytes}: 実体が残っている成果物の合計バイト数</li>
 * </ul>
 * 値は DB から 30 秒ごとに読み直す。Prometheus の収集のたびに DB を数えないようにするため。
 */
@Component
public class JobMetrics {

    private static final Logger log = LoggerFactory.getLogger(JobMetrics.class);

    private final JobRepository jobs;
    private final ArtifactRecordRepository artifacts;
    private final Map<JobType, AtomicLong> pending = new EnumMap<>(JobType.class);
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong artifactBytes = new AtomicLong();

    public JobMetrics(JobRepository jobs, ArtifactRecordRepository artifacts, MeterRegistry registry) {
        this.jobs = jobs;
        this.artifacts = artifacts;
        for (JobType type : JobType.values()) {
            AtomicLong value = new AtomicLong();
            pending.put(type, value);
            Gauge.builder("qg.jobs.pending", value, AtomicLong::get)
                    .tag("type", type.name())
                    .description("実行待ちのジョブ数")
                    .register(registry);
        }
        Gauge.builder("qg.jobs.dead", dead, AtomicLong::get)
                .description("恒久的に失敗したジョブ数").register(registry);
        Gauge.builder("qg.artifacts.bytes", artifactBytes, AtomicLong::get)
                .description("実体が残っている成果物の合計バイト数").baseUnit("bytes").register(registry);
    }

    @Scheduled(fixedDelayString = "${quality-gate.metrics-refresh-interval:30000}", initialDelay = 5000)
    public void refresh() {
        try {
            pending.forEach((type, value) -> value.set(jobs.countByStatusAndType(JobStatus.PENDING, type)));
            dead.set(jobs.countByStatus(JobStatus.DEAD));
            artifactBytes.set(artifacts.sumStoredBytes());
        } catch (RuntimeException e) {
            // メトリクスの更新の失敗で業務を止めない。値は前回のまま残る
            log.warn("ジョブとストレージのメトリクスを更新できませんでした: {}", e.getMessage());
        }
    }
}
