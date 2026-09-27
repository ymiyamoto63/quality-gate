package com.qualitygate.config;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.gate.ConfigValidationError;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.storage.ArtifactStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 設定の参照（S-06）。直近の Run に送られた合格ラインを表示する。
 *
 * <p>設定は {@code collector/targets/<owner>__<name>.gate.yml} を Git で管理し、収集ランナーが Run ごとに送る（DD-13）。
 * 画面は表示するだけで、編集は受け付けない。置き場所を 1 つにして、どちらが効いているか迷わないようにする。
 */
@Service
public class ConfigQueryService {

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final ArtifactStore artifactStore;
    private final GateConfigParser parser;

    public ConfigQueryService(MonitoredRepositoryRepository repositories, RunRepository runs,
                              ArtifactRecordRepository artifacts, ArtifactStore artifactStore,
                              GateConfigParser parser) {
        this.repositories = repositories;
        this.runs = runs;
        this.artifacts = artifacts;
        this.artifactStore = artifactStore;
        this.parser = parser;
    }

    /**
     * 直近の Run の設定ファイルを検証し直して、行番号付きのエラーとともに返す。
     *
     * <p>Run に記録した文字列をそのまま返さず検証し直すのは、画面が
     * 「該当行の直下」にエラーを出すため、行番号とパスを構造で持つ必要があるから。
     */
    @Transactional(readOnly = true)
    public ConfigResponses.RepositoryConfig get(UUID repositoryId) {
        if (!repositories.existsById(repositoryId)) {
            throw ApiException.notFound("リポジトリ", repositoryId);
        }
        Optional<Run> latest = runs.findByRepositoryIdOrderByMeasuredAtDesc(repositoryId,
                PageRequest.of(0, 1)).stream().findFirst();
        if (latest.isEmpty()) {
            return new ConfigResponses.RepositoryConfig(null, null, null, null, List.of());
        }
        Run run = latest.get();
        Optional<String> yaml = artifacts.findByRunId(run.getId()).stream()
                .filter(a -> a.getType().isConfiguration())
                .findFirst()
                .flatMap(this::read);
        return new ConfigResponses.RepositoryConfig(run.getId(), run.getMeasuredAt(), run.getConfigCommitSha(),
                yaml.orElse(null), yaml.map(this::validate).orElse(List.of()));
    }

    private List<ConfigResponses.ValidationErrorItem> validate(String yaml) {
        try {
            parser.parse(yaml);
            return List.of();
        } catch (ConfigValidationException e) {
            return e.errors().stream().map(ConfigQueryService::errorOf).toList();
        }
    }

    private Optional<String> read(ArtifactRecord artifact) {
        if (artifact.getDeletedAt() != null) {
            return Optional.empty();
        }
        try (InputStream in = artifactStore.open(artifact.getStorageKey())) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static ConfigResponses.ValidationErrorItem errorOf(ConfigValidationError error) {
        return new ConfigResponses.ValidationErrorItem(error.line(),
                error.path() == null ? "" : error.path(), error.message());
    }
}
