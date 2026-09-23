package com.qualitygate.ingest;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.RunSkippedMetric;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.domain.model.MutationScope;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.ingest.dto.CreateRunRequest;
import com.qualitygate.ingest.dto.SkippedMetricRequest;
import com.qualitygate.platform.config.QualityGateProperties;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.platform.storage.StoredArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 取り込み（Run 作成・成果物受領・確定）のユースケース。 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** 計測環境の名前の上限。measurements.variant の幅に合わせる。 */
    private static final int MAX_ENVIRONMENT_NAME = 64;

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final RunSkippedMetricRepository skippedMetrics;
    private final ArtifactRecordRepository artifacts;
    private final JobRepository jobs;
    private final ArtifactStore artifactStore;
    private final QualityGateProperties properties;
    private final ObjectMapper objectMapper;

    public IngestService(MonitoredRepositoryRepository repositories, RunRepository runs,
                         RunSkippedMetricRepository skippedMetrics,
                         ArtifactRecordRepository artifacts, JobRepository jobs,
                         ArtifactStore artifactStore, QualityGateProperties properties,
                         ObjectMapper objectMapper) {
        this.repositories = repositories;
        this.runs = runs;
        this.skippedMetrics = skippedMetrics;
        this.artifacts = artifacts;
        this.jobs = jobs;
        this.artifactStore = artifactStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Run createRun(UUID authenticatedRepositoryId, CreateRunRequest request) {
        MonitoredRepository repository = repositories
                .findByOwnerAndName(request.owner(), request.name())
                .orElseThrow(() -> ApiException.notFound("リポジトリ", request.repository()));

        if (!repository.getId().equals(authenticatedRepositoryId)) {
            throw new ApiException(ErrorCode.REPOSITORY_MISMATCH,
                    "この Ingest Token は %s に対して発行されていません".formatted(request.repository()));
        }

        // 同一コミットへの再送信は上書きせず、attempt を増やした新しい Run とする。
        int attempt = runs.findMaxAttempt(repository.getId(), request.commitSha()) + 1;

        Run run = new Run(Uuid7.generate(), repository.getId(), request.commitSha(),
                request.branch(), request.runnerType(), request.triggeredBy(),
                request.measuredAt(), attempt);
        run.setBaseCommitSha(request.baseCommitSha());
        run.setPullRequestNumber(request.pullRequestNumber());
        run.setCiRunUrl(request.ciRunUrl());
        runs.save(run);

        recordSkippedMetrics(run.getId(), request.skippedMetricsOrEmpty());
        log.info("Run を作成しました runId={} repository={} commit={} attempt={} runner={}",
                run.getId(), request.repository(), request.commitSha(), attempt, request.runnerType());
        return run;
    }

    /**
     * スキップ申告を記録する。
     *
     * <p>受理するかどうかは<strong>判定時に決める</strong>。取り込み時点では
     * 設定（{@code execution.skippable_metrics}）が未解決であり、設定は成果物として
     * 後から届くためである。ここで暫定値を入れると、設定と食い違ったまま記録が残る。
     */
    private void recordSkippedMetrics(UUID runId, List<SkippedMetricRequest> requested) {
        requested.stream()
                .map(s -> new RunSkippedMetric(runId, s.metricId(), s.reason()))
                .forEach(skippedMetrics::save);
    }

    @Transactional
    public ArtifactRecord storeArtifact(UUID authenticatedRepositoryId, UUID runId,
                                        ArtifactType type, String filename,
                                        String componentName, String scope, String metadata,
                                        InputStream content, long declaredSize) {
        Run run = loadOwnedRun(authenticatedRepositoryId, runId);

        if (!run.getStatus().acceptsArtifacts()) {
            throw new ApiException(ErrorCode.RUN_ALREADY_FINALIZED,
                    "この Run は既に確定しているため成果物を追加できません（status=%s）"
                            .formatted(run.getStatus()));
        }
        if (declaredSize > properties.maxArtifactBytes()) {
            throw new ApiException(ErrorCode.ARTIFACT_TOO_LARGE,
                    "ファイルサイズが上限 %dMB を超えています（受信: %dMB）"
                            .formatted(properties.maxArtifactBytes() / 1024 / 1024,
                                    declaredSize / 1024 / 1024));
        }
        validateMetadata(type, metadata);

        // ファイルを先に保存し、成功後に DB へ記録する。逆順にすると、
        // 参照先ファイルの無いレコードという扱いにくい壊れ方をする。
        StoredArtifact stored = artifactStore.store(runId.toString(), filename, content);

        long total = artifacts.sumSizeBytesByRunId(runId) + stored.sizeBytes();
        if (total > properties.maxRunBytes()) {
            artifactStore.delete(stored.storageKey());
            throw new ApiException(ErrorCode.ARTIFACT_TOO_LARGE,
                    "Run あたりの合計サイズが上限 %dMB を超えています"
                            .formatted(properties.maxRunBytes() / 1024 / 1024));
        }

        ArtifactRecord record = new ArtifactRecord(Uuid7.generate(), runId, type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(),
                componentName, scope, metadata);
        artifacts.save(record);
        run.markUploading();
        return record;
    }

    @Transactional
    public Run finalizeRun(UUID authenticatedRepositoryId, UUID runId) {
        Run run = loadOwnedRun(authenticatedRepositoryId, runId);
        if (!run.getStatus().acceptsArtifacts()) {
            throw new ApiException(ErrorCode.RUN_ALREADY_FINALIZED,
                    "この Run は既に確定しています（status=%s）".formatted(run.getStatus()));
        }
        run.finalizeIngest();

        // 判定は非同期で行う。同期にすると CI の待ち時間が判定時間ぶん延びる。
        jobs.save(new Job(Uuid7.generate(), JobType.EVALUATE_RUN, runId.toString(),
                toJson(Map.of("runId", runId.toString()))));
        log.info("Run を確定しました runId={} 判定ジョブを登録", runId);
        return run;
    }

    @Transactional(readOnly = true)
    public Run loadOwnedRun(UUID authenticatedRepositoryId, UUID runId) {
        Run run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Run", runId));
        if (!run.getRepositoryId().equals(authenticatedRepositoryId)) {
            // 他リポジトリの Run の存在自体を明かさないため 404 を返す。
            throw ApiException.notFound("Run", runId);
        }
        return run;
    }

    public String detailUrl(UUID runId) {
        return "%s/runs/%s".formatted(properties.baseUrl(), runId);
    }

    /**
     * 成果物の種類ごとに必須のメタデータを検証する。
     *
     * <p>取り込み時に拒否するのは、判定時に気づいても CI のログには残らないため。
     * 計測条件の欠けた値は、後から正しい系列に振り分けられない。
     */
    private void validateMetadata(ArtifactType type, String metadata) {
        JsonNode node = parseMetadata(metadata);

        if (type.requiresEnvironmentMetadata()) {
            validateEnvironment(node.get("environment"));
        }
        if (type == ArtifactType.PIT_XML) {
            JsonNode scope = node.get(MutationScope.METADATA_KEY);
            String allowed = Arrays.stream(MutationScope.values()).map(MutationScope::wire)
                    .collect(Collectors.joining(" / "));
            if (scope == null || scope.isNull()) {
                // 変更範囲だけの値と全量の値は比較できない。どちらか分からない値は
                // 前回比にもトレンドにも置き場所がない（docs/02-metrics-spec.md M-02）
                throw new ApiException(ErrorCode.MUTATION_SCOPE_MISSING,
                        "PIT の成果物には metadata の %s（%s）が必要です"
                                .formatted(MutationScope.METADATA_KEY, allowed));
            }
            if (!scope.isString() || MutationScope.find(scope.asString()).isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "metadata の %s は %s のいずれかを指定してください（受信値: %s）"
                                .formatted(MutationScope.METADATA_KEY, allowed, scope));
            }
        }
        if (type == ArtifactType.OASDIFF_JSON) {
            JsonNode baseMissing = node.get(ParseContext.BASE_SPEC_MISSING);
            // "true" のような文字列を読み流すと、新規 API の申告が黙って無視される
            if (baseMissing != null && !baseMissing.isNull() && !baseMissing.isBoolean()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "metadata の %s は true / false で指定してください（受信値: %s）"
                                .formatted(ParseContext.BASE_SPEC_MISSING, baseMissing));
            }
        }
    }

    /**
     * 性能計測の環境。名前はトレンドの系列を分ける軸になるため必須とする。
     * 名前の無い値は、どの環境の性能か分からず比較できない。
     */
    private static void validateEnvironment(JsonNode environment) {
        if (environment == null || environment.isNull()) {
            throw new ApiException(ErrorCode.PERFORMANCE_METADATA_MISSING,
                    "性能計測の成果物には environment メタデータが必要です"
                            + "（name / runner / cpu / memory / datasetProfile など）");
        }
        JsonNode name = environment.isObject() ? environment.get("name") : null;
        if (name == null || !name.isString() || name.asString().isBlank()) {
            throw new ApiException(ErrorCode.PERFORMANCE_METADATA_MISSING,
                    "environment.name（計測環境の名前。例: perf-staging）が必要です");
        }
        if (name.asString().strip().length() > MAX_ENVIRONMENT_NAME) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "environment.name は %d 文字以内で指定してください".formatted(MAX_ENVIRONMENT_NAME));
        }
    }

    /** 未指定は空のオブジェクトとして扱う。指定されたなら JSON オブジェクトでなければならない。 */
    private JsonNode parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(metadata);
        } catch (JacksonException e) {
            // 入力の誤りであり、再送すれば直るものではない。そのまま業務例外にする。
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "metadata が JSON として解釈できません: " + e.getMessage());
        }
        if (!node.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "metadata は JSON オブジェクト（{...}）で指定してください");
        }
        return node;
    }

    private String toJson(Map<String, ?> value) {
        // Jackson 3 の書き出しは非チェック例外。ここで失敗するのは実装の誤りに限られる。
        return objectMapper.writeValueAsString(value);
    }
}
