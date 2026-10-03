package com.qualitygate.ingest;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.ingest.dto.CreateRunRequest;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/** 取り込み（Run 作成・成果物受領・確定）のユースケース。 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** 計測環境の名前の上限。measurements.variant の幅に合わせる。 */
    private static final int MAX_ENVIRONMENT_NAME = 64;

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final ArtifactRecordRepository artifacts;
    private final ArtifactStore artifactStore;
    private final QualityGateProperties properties;
    private final ObjectMapper objectMapper;

    public IngestService(MonitoredRepositoryRepository repositories, RunRepository runs,
                         ArtifactRecordRepository artifacts,
                         ArtifactStore artifactStore, QualityGateProperties properties,
                         ObjectMapper objectMapper) {
        this.repositories = repositories;
        this.runs = runs;
        this.artifacts = artifacts;
        this.artifactStore = artifactStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Run createRun(CreateRunRequest request) {
        // 計測の対象は 1 つだけ（QG_REPOSITORY）。取り違えた送信で別のアプリの結果が混ざらないよう拒否する
        if (!properties.repository().equals(request.repository())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "計測対象のリポジトリは %s です（受信: %s）。QG_REPOSITORY を確かめてください"
                            .formatted(properties.repository(), request.repository()));
        }
        MonitoredRepository repository = repositories
                .findByOwnerAndName(request.owner(), request.name())
                .orElseGet(() -> register(request));

        // 同一コミットへの再送信は上書きせず、attempt を増やした新しい Run とする。
        int attempt = runs.findMaxAttempt(repository.getId(), request.commitSha()) + 1;

        Run run = new Run(Uuid7.generate(), repository.getId(), request.commitSha(),
                request.branch(), request.measuredAt(), attempt);
        run.setBaseCommitSha(request.baseCommitSha());
        run.setCiRunUrl(request.ciRunUrl());
        run.setTags(request.tagsOrEmpty());
        runs.save(run);
        log.info("Run を作成しました runId={} repository={} commit={} attempt={}",
                run.getId(), request.repository(), request.commitSha(), attempt);
        return run;
    }

    private MonitoredRepository register(CreateRunRequest request) {
        log.info("リポジトリを登録しました repository={}", request.repository());
        return repositories.save(new MonitoredRepository(Uuid7.generate(), request.owner(), request.name()));
    }

    @Transactional
    public void storeArtifact(UUID runId,
                                        ArtifactType type, String filename,
                                        String componentName, String metadata,
                                        InputStream content, long declaredSize) {
        Run run = loadRun(runId);

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

        // 同じ種別・同じファイル名の再送は置き換える（CI のリトライや、作り直した成果物の送り直し）。
        // 確定前の Run に限るため、判定済みの結果が変わることはない
        Optional<ArtifactRecord> replaced = artifacts.findByRunIdAndTypeAndFilename(runId, type, filename);

        // ファイルを先に保存し、成功後に DB へ記録する。逆順にすると、
        // 参照先ファイルの無いレコードという扱いにくい壊れ方をする。
        // 保存先は成果物ごとに分ける。ファイル名だけで決めると、再送や種別違いの同名ファイルが
        // 既存の成果物のファイルを上書きし、記録（サイズ・ハッシュ）と中身が食い違う
        UUID artifactId = Uuid7.generate();
        StoredArtifact stored = artifactStore.store(runId.toString(), artifactId + "_" + filename, content);

        long total = artifacts.sumSizeBytesByRunId(runId) + stored.sizeBytes()
                - replaced.map(ArtifactRecord::getSizeBytes).orElse(0L);
        if (total > properties.maxRunBytes()) {
            artifactStore.delete(stored.storageKey());
            throw new ApiException(ErrorCode.ARTIFACT_TOO_LARGE,
                    "Run あたりの合計サイズが上限 %dMB を超えています"
                            .formatted(properties.maxRunBytes() / 1024 / 1024));
        }

        replaced.ifPresent(old -> {
            artifacts.delete(old);
            // 一意制約（run_id, type, filename）に当たらないよう、新しい行より先に消す
            artifacts.flush();
            // 古いファイルは確定（コミット）してから消す。ロールバックされたら古い成果物が残る
            deleteAfterCommit(old.getStorageKey());
            log.info("成果物を置き換えました runId={} type={} filename={}", runId, type.wire(), filename);
        });

        ArtifactRecord record = new ArtifactRecord(artifactId, runId, type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(),
                componentName, metadata);
        artifacts.save(record);
        run.markUploading();
    }

    private void deleteAfterCommit(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    artifactStore.delete(storageKey);
                } catch (RuntimeException e) {
                    // 取り込みは成功している。残ったファイルは判定に使われない
                    log.warn("置き換えた成果物の古いファイルを消せませんでした key={} 理由={}", storageKey, e.getMessage());
                }
            }
        });
    }

    @Transactional
    public Run finalizeRun(UUID runId) {
        Run run = loadRun(runId);
        if (!run.getStatus().acceptsArtifacts()) {
            throw new ApiException(ErrorCode.RUN_ALREADY_FINALIZED,
                    "この Run は既に確定しています（status=%s）".formatted(run.getStatus()));
        }
        run.finalizeIngest();
        log.info("Run を確定しました runId={}", runId);
        return run;
    }

    private Run loadRun(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> ApiException.notFound("Run", runId));
    }

    public String detailUrl(Run run) {
        return "%s/?ref=%s".formatted(properties.baseUrl(), run.getCommitSha());
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
     * 性能計測の環境。名前は前回値を比べる単位になるため必須とする。
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
}
