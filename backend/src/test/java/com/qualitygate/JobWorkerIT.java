package com.qualitygate;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.JobStatus;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.job.JobWorker;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.platform.storage.StoredArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ジョブの実行とトランザクション境界を検証する。
 *
 * <p>ハンドラ内の {@code @Transactional} が例外でロールバック専用になると、
 * 外側のトランザクションのコミットが失敗してジョブの状態更新まで巻き戻る。
 * その結果、同じジョブが毎秒再実行され続ける。ここで固定する。
 */
@SpringBootTest
@AbstractIntegrationTest
class JobWorkerIT {

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired JobRepository jobs;
    @Autowired GateConfigRepository gateConfigs;
    @Autowired ArtifactStore artifactStore;
    @Autowired JobWorker worker;

    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        jobs.deleteAll();
        artifacts.deleteAll();
        runs.deleteAll();
        gateConfigs.deleteAll();
        repositories.deleteAll();
        users.deleteAll();

        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(),
                "ymiyamoto63", "quality-gate", admin.getId())).getId();
    }

    @Test
    void 設定が不正なジョブは一度で打ち切られ再実行されない() {
        Run run = newRun("1111111111111111111111111111111111111111");
        attachConfig(run, """
                version: 1
                metrics:
                  branch_coverage:
                    threshold: "75%"
                """);
        UUID jobId = enqueue(run);

        worker.poll();

        Job job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLastError()).contains("設定の検証に失敗しました");

        // 打ち切られているので、次のポーリングでは何も起きない
        worker.poll();
        assertThat(jobs.findById(jobId).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void 設定が不正ならRunは処理失敗として記録される() {
        Run run = newRun("2222222222222222222222222222222222222222");
        attachConfig(run, """
                version: 1
                metrics:
                  mutation_scores:
                    threshold: 60
                """);
        enqueue(run);

        worker.poll();

        Run failed = runs.findById(run.getId()).orElseThrow();
        // 判定結果 FAIL ではなく処理失敗。品質の問題と設定の誤りを混同させない。
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getVerdict()).isNull();
        assertThat(failed.getErrorCode()).isEqualTo("CONFIG_VALIDATION_FAILED");
        assertThat(failed.getErrorDetail())
                .contains("mutation_score")
                .contains("行目");
    }

    @Test
    void 設定が不正な場合は設定版を保存しない() {
        Run run = newRun("3333333333333333333333333333333333333333");
        attachConfig(run, "version: 1\nexclusion: []\n");
        enqueue(run);

        worker.poll();

        // 検証を通らなかった設定を版として残すと、履歴に不正な設定が混ざる
        assertThat(gateConfigs.count()).isZero();
    }

    @Test
    void 正常なジョブは成功として記録される() {
        Run run = newRun("4444444444444444444444444444444444444444");
        attachConfig(run, """
                version: 1
                metrics:
                  vulnerabilities:
                    enabled: false
                  cyclomatic_complexity:
                    enabled: false
                  branch_coverage:
                    enabled: false
                """);
        UUID jobId = enqueue(run);

        worker.poll();

        assertThat(jobs.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.SUCCEEDED);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.EVALUATED);
    }

    private Run newRun(String commitSha) {
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, "main",
                "ci", Instant.parse("2026-09-22T00:00:00Z"), 1);
        run.finalizeIngest();
        return runs.save(run);
    }

    private void attachConfig(Run run, String yaml) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), ".quality-gate.yml",
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(),
                ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", stored.sizeBytes(),
                stored.sha256(), stored.storageKey(), null, null, null));
    }

    private UUID enqueue(Run run) {
        return jobs.save(new Job(Uuid7.generate(), JobType.EVALUATE_RUN,
                run.getId().toString(),
                "{\"runId\":\"%s\"}".formatted(run.getId()))).getId();
    }
}
