package com.qualitygate.config;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.GateConfig;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.gate.ConfigValidationError;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.gate.GateConfigDocument;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.security.Actor;
import com.qualitygate.platform.security.CurrentUser;
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
 * 設定の参照と UI からの更新（S-06 / FR-02-3）。
 *
 * <p>優先順位は {@code .quality-gate.yml（CI が送信） > UI 設定 > システム既定値}。
 * ファイルで管理されているリポジトリでは UI 編集を受け付けない。優先順位を知らずに
 * UI で変更し、反映されずに混乱する事故を防ぐ（docs/initial/08-screen-design.md 4.6）。
 */
@Service
public class ConfigQueryService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };
    private static final String TARGET = "REPOSITORY";

    /** 設定ファイルが無いリポジトリの表示と、UI 編集の初期値。 */
    static final String DEFAULT_YAML = """
            version: 1
            enforcement: report-only
            on_missing_report: fail

            execution:
              skippable_metrics: [mutation_score, performance]
              full_measurement_interval_days: 7
              reference_only_environments: [github-hosted]

            metrics:
              branch_coverage:
                threshold: 75
                diff_threshold: 80
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
                min_success_rate: 100
                min_test_count: 1
                breaking_changes: 0
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
    private final CurrentUser currentUser;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("java:S107")
    public ConfigQueryService(GateConfigRepository configs,
                              MonitoredRepositoryRepository repositories, RunRepository runs,
                              ArtifactRecordRepository artifacts, ArtifactStore artifactStore,
                              GateConfigParser parser, CurrentUser currentUser,
                              AuditLogger auditLogger, ObjectMapper objectMapper) {
        this.configs = configs;
        this.repositories = repositories;
        this.runs = runs;
        this.artifacts = artifacts;
        this.artifactStore = artifactStore;
        this.parser = parser;
        this.currentUser = currentUser;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ConfigResponses.RepositoryConfig get(UUID repositoryId) {
        requireRepository(repositoryId);
        List<GateConfig> versions = configs.findByRepositoryIdOrderByVersionDesc(repositoryId);
        ConfigResponses.ConfigVersion current = versions.isEmpty() ? null : versionOf(versions.getFirst());
        return new ConfigResponses.RepositoryConfig(current,
                versions.stream().map(ConfigQueryService::historyOf).toList(),
                latestValidation(repositoryId),
                isEditable(versions),
                DEFAULT_YAML);
    }

    /**
     * UI から設定を更新する。検証を通った内容だけを新しい版として保存する。
     * 内容が同じなら版を増やさない（変更履歴がノイズで埋まるため）。
     */
    @Transactional
    public void update(UUID repositoryId, String rawYaml) {
        Actor actor = currentUser.actor();
        requireRepository(repositoryId);
        List<GateConfig> versions = configs.findByRepositoryIdOrderByVersionDesc(repositoryId);
        if (!isEditable(versions)) {
            throw new ApiException(ErrorCode.CONFIG_MANAGED_BY_FILE,
                    "このリポジトリの設定は .quality-gate.yml で管理されており、ファイルが優先されます。"
                            + "設定を変えるにはファイルを編集してください");
        }

        GateConfigDocument document;
        try {
            document = parser.parse(rawYaml);
        } catch (ConfigValidationException e) {
            throw new ApiException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "設定に %d 件の誤りがあります".formatted(e.errors().size()),
                    Map.of("errors", e.errors().stream().map(ConfigQueryService::errorOf).toList()));
        }

        String hash = GateConfigService.sha256(rawYaml);
        if (configs.findByRepositoryIdAndContentHash(repositoryId, hash).isPresent()) {
            return;
        }
        int before = versions.isEmpty() ? 0 : versions.getFirst().getVersion();
        GateConfig saved = configs.save(new GateConfig(Uuid7.generate(), repositoryId,
                configs.findMaxVersion(repositoryId) + 1, GateConfig.SOURCE_UI, null, hash,
                rawYaml, objectMapper.writeValueAsString(document)));
        auditLogger.record(actor, AuditAction.CONFIG_UPDATED, TARGET, repositoryId,
                before == 0 ? null : Map.of("version", before),
                Map.of("version", saved.getVersion(), "sourceType", GateConfig.SOURCE_UI));
    }

    /** 最新の版がファイル由来でなければ UI から編集できる。 */
    private static boolean isEditable(List<GateConfig> versions) {
        return versions.isEmpty()
                || !GateConfig.SOURCE_FILE.equals(versions.getFirst().getSourceType());
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

    private static ConfigResponses.ConfigHistoryItem historyOf(GateConfig config) {
        return new ConfigResponses.ConfigHistoryItem(config.getId(), config.getVersion(),
                config.getSourceType(), config.getSourceCommitSha(), config.getCreatedAt());
    }

    private static ConfigResponses.ValidationErrorItem errorOf(ConfigValidationError error) {
        return new ConfigResponses.ValidationErrorItem(error.line(),
                error.path() == null ? "" : error.path(), error.message());
    }

    private void requireRepository(UUID repositoryId) {
        if (!repositories.existsById(repositoryId)) {
            throw ApiException.notFound("リポジトリ", repositoryId);
        }
    }
}
