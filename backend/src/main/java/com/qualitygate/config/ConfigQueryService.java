package com.qualitygate.config;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.GateConfig;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.gate.ConfigValidationError;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.storage.ArtifactStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 設定の参照（S-06）。
 *
 * <p>設定は {@code collector/targets/<owner>__<name>.gate.yml} を Git で管理し、収集ランナーが Run ごとに送る（DD-13）。
 * 画面は表示するだけで、編集は受け付けない。置き場所を 1 つにして、どちらが効いているか迷わないようにする。
 */
@Service
public class ConfigQueryService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /** 設定版が無いリポジトリの表示（既定値）。 */
    static final String DEFAULT_YAML = """
            version: 1

            execution:
              skippable_metrics: [mutation_score, performance]

            metrics:
              branch_coverage:
                threshold: 75
                warn_below: 80
              mutation_score:
                threshold: 60
              performance:
                p95_ms: 500
                arrival_rate_rps: 50
                error_rate_pct: 0.1
              vulnerabilities:
                max_critical: 0
                max_high: 0
              cyclomatic_complexity:
                max_complexity: 15
                warn_from: 11
              api_contract:
                breaking_changes: 0
              test_results:
                min_success_rate: 100
                min_test_count: 1
                max_skipped_increase: 0
              accessibility:
                standard: wcag22aa
                max_critical: 0
            """;

    private final GateConfigRepository configs;
    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final ArtifactStore artifactStore;
    private final GateConfigParser parser;
    private final ObjectMapper objectMapper;

    public ConfigQueryService(GateConfigRepository configs,
                              MonitoredRepositoryRepository repositories, RunRepository runs,
                              ArtifactRecordRepository artifacts, ArtifactStore artifactStore,
                              GateConfigParser parser, ObjectMapper objectMapper) {
        this.configs = configs;
        this.repositories = repositories;
        this.runs = runs;
        this.artifacts = artifacts;
        this.artifactStore = artifactStore;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ConfigResponses.RepositoryConfig get(UUID repositoryId) {
        requireRepository(repositoryId);
        ConfigResponses.ConfigVersion current = configs.findFirstByRepositoryIdOrderByVersionDesc(repositoryId)
                .map(this::versionOf).orElse(null);
        return new ConfigResponses.RepositoryConfig(current,
                latestValidation(repositoryId),
                DEFAULT_YAML);
    }

    /**
     * 直近の Run が設定の検証エラーで処理失敗していれば、その設定ファイルを
     * 検証し直して行番号付きのエラーを返す。
     *
     * <p>Run に記録した文字列をそのまま返さず検証し直すのは、画面が
     * 「該当行の直下」にエラーを出すため、行番号とパスを構造で持つ必要があるから。
     */
    private ConfigResponses.ConfigValidation latestValidation(UUID repositoryId) {
        Optional<Run> latest = runs.findByRepositoryIdOrderByMeasuredAtDesc(repositoryId,
                        PageRequest.of(0, 1)).stream().findFirst()
                .filter(run -> run.getStatus() == RunStatus.FAILED
                        && "CONFIG_VALIDATION_FAILED".equals(run.getErrorCode()));
        if (latest.isEmpty()) {
            return new ConfigResponses.ConfigValidation(true, List.of(), null, null);
        }
        Run run = latest.get();
        Optional<String> yaml = artifacts.findByRunId(run.getId()).stream()
                .filter(a -> a.getType().isConfiguration())
                .findFirst()
                .flatMap(this::read);
        List<ConfigResponses.ValidationErrorItem> errors = yaml
                .map(this::validate)
                .orElseGet(() -> List.of(new ConfigResponses.ValidationErrorItem(null, "",
                        run.getErrorDetail() == null ? "設定の検証に失敗しました" : run.getErrorDetail())));
        return new ConfigResponses.ConfigValidation(errors.isEmpty(), errors, run.getId(),
                yaml.orElse(null));
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

    private ConfigResponses.ConfigVersion versionOf(GateConfig config) {
        return new ConfigResponses.ConfigVersion(config.getId(), config.getVersion(),
                config.getSourceType(), config.getSourceCommitSha(), config.getCreatedAt(),
                config.getRawYaml(), objectMapper.readValue(config.getParsed(), JSON_OBJECT));
    }

    static ConfigResponses.ValidationErrorItem errorOf(ConfigValidationError error) {
        return new ConfigResponses.ValidationErrorItem(error.line(),
                error.path() == null ? "" : error.path(), error.message());
    }

    private void requireRepository(UUID repositoryId) {
        if (!repositories.existsById(repositoryId)) {
            throw ApiException.notFound("リポジトリ", repositoryId);
        }
    }
}
