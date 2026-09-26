package com.qualitygate;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.pipeline.RunEvaluationPipeline;
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
 * 取り込みの確定と再評価から呼ぶ、その場での判定（D-27）の失敗の扱いを検証する。
 *
 * <p>判定に失敗しても例外は投げず、Run を「処理失敗」として記録する。判定結果 FAIL（品質の問題）と
 * 処理の失敗（設定の誤りなど）を混同させないため。
 */
@SpringBootTest
@AbstractIntegrationTest
class RunEvaluationPipelineIT {

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired GateConfigRepository gateConfigs;
    @Autowired ArtifactStore artifactStore;
    @Autowired RunEvaluationPipeline pipeline;

    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(),
                "ymiyamoto63", "quality-gate", admin.getId())).getId();
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

        Run failed = pipeline.evaluate(run.getId());

        // 判定結果 FAIL ではなく処理失敗。品質の問題と設定の誤りを混同させない。
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getVerdict()).isNull();
        assertThat(failed.getErrorCode()).isEqualTo("CONFIG_VALIDATION_FAILED");
        assertThat(failed.getErrorDetail())
                .contains("mutation_score")
                .contains("行目");
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void 設定が不正な場合は設定版を保存しない() {
        Run run = newRun("3333333333333333333333333333333333333333");
        attachConfig(run, "version: 1\nexclusion: []\n");

        pipeline.evaluate(run.getId());

        // 検証を通らなかった設定を版として残すと、履歴に不正な設定が混ざる
        assertThat(gateConfigs.count()).isZero();
    }

    @Test
    void 想定外の失敗も処理失敗として記録し例外を投げない() {
        Run run = newRun("5555555555555555555555555555555555555555");
        ArtifactRecord config = attachConfig(run, "version: 1\n");
        // 設定ファイルの実体が無い（読み出しで失敗する）
        artifactStore.delete(config.getStorageKey());

        Run failed = pipeline.evaluate(run.getId());

        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("EVALUATION_FAILED");
    }

    @Test
    void 正常なRunは判定済みになる() {
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

        Run evaluated = pipeline.evaluate(run.getId());

        assertThat(evaluated.getStatus()).isEqualTo(RunStatus.EVALUATED);
        assertThat(evaluated.getVerdict()).isNotNull();
        assertThat(evaluated.getErrorCode()).isNull();
    }

    private Run newRun(String commitSha) {
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, "main",
                "ci", Instant.parse("2026-09-22T00:00:00Z"), 1);
        run.finalizeIngest();
        return runs.save(run);
    }

    private ArtifactRecord attachConfig(Run run, String yaml) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), ".quality-gate.yml",
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(),
                ArtifactType.QUALITY_GATE_CONFIG, ".quality-gate.yml", stored.sizeBytes(),
                stored.sha256(), stored.storageKey(), null, null, null));
    }
}
