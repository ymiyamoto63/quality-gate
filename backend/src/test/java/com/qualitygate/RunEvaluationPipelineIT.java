package com.qualitygate;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
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
 * 取り込みの確定から呼ぶ、その場での判定（DD-15）の失敗の扱いを検証する。
 *
 * <p>判定に失敗しても例外は投げず、Run を「処理失敗」として記録する。判定結果 FAIL（品質の問題）と
 * 処理の失敗を混同させないため。
 */
@SpringBootTest
@AbstractIntegrationTest
class RunEvaluationPipelineIT {

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired RunRepository runs;
    @Autowired MeasurementRepository measurements;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired ArtifactStore artifactStore;
    @Autowired RunEvaluationPipeline pipeline;

    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        IntegrationCleanup.deleteAll(jdbc);
        repositoryId = repositories.save(new MonitoredRepository(Uuid7.generate(),
                "ymiyamoto63", "quality-gate", "main")).getId();
    }

    @Test
    void 想定外の失敗は処理失敗として記録し例外を投げない() {
        Run run = newRun("5555555555555555555555555555555555555555");
        ArtifactRecord report = attach(run, "jacoco.xml", "<report/>");
        // 成果物の実体が無い（読み出しで失敗する）
        artifactStore.delete(report.getStorageKey());

        Run failed = pipeline.evaluate(run.getId());

        // 判定結果 FAIL ではなく処理失敗。品質の問題と処理の失敗を混同させない。
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getVerdict()).isNull();
        assertThat(failed.getErrorCode()).isEqualTo("EVALUATION_FAILED");
    }

    /** 測っていないものを合格とはみなさない（fail-closed）。 */
    @Test
    void 成果物の無い指標は計測エラーになり不合格になる() {
        Run run = newRun("4444444444444444444444444444444444444444");

        Run evaluated = pipeline.evaluate(run.getId());

        assertThat(evaluated.getStatus()).isEqualTo(RunStatus.EVALUATED);
        assertThat(evaluated.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(evaluated.getErrorCode()).isNull();
        assertThat(measurements.findByRunId(run.getId()))
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MeasurementStatus.ERROR));
    }

    private Run newRun(String commitSha) {
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, "main",
                "ci", Instant.parse("2026-09-22T00:00:00Z"), 1);
        run.finalizeIngest();
        return runs.save(run);
    }

    private ArtifactRecord attach(Run run, String filename, String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(),
                ArtifactType.JACOCO_XML, filename, stored.sizeBytes(),
                stored.sha256(), stored.storageKey(), "backend", null));
    }
}
