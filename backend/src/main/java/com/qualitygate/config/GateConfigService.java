package com.qualitygate.config;

import com.qualitygate.domain.gate.ConfigValidationError;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.gate.GateConfigDocument;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.GateConfig;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 判定に使う設定を解決する。
 *
 * <p>設定は CI が {@code quality-gate-config} 型の成果物として送る。
 * quality-gate 自身が GitHub API で取得する方式も検討したが、
 * <strong>取り込み型（要件定義書 D-1）の下では CI をすでに信頼している</strong>
 * ためである。CI は計測値そのものを送っており、設定だけを別経路で取っても
 * 守れる範囲は増えない。代わりに GitHub の認証情報と障害点を持ち込まずに済む。
 *
 * <p>将来、多重防御として GitHub からの直接取得を足す余地は
 * {@code sourceType} と {@code sourceCommitSha} に残してある。
 */
@Service
public class GateConfigService {

    private static final Logger log = LoggerFactory.getLogger(GateConfigService.class);
    private static final int MAX_CONFIG_BYTES = 256 * 1024;

    private final GateConfigRepository configs;
    private final GateConfigParser parser;
    private final ArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    public GateConfigService(GateConfigRepository configs, GateConfigParser parser,
                             ArtifactStore artifactStore, ObjectMapper objectMapper) {
        this.configs = configs;
        this.parser = parser;
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Run に対応する設定を解決する。
     *
     * <p>設定ファイルが提出されていなければシステム既定値を使う。
     * 検証エラーは {@link ConfigValidationException} として投げる。
     * 不正な設定で判定を続けると、意図しないしきい値で合格が出てしまう。
     */
    @Transactional
    public Resolved resolve(Run run, List<ArtifactRecord> artifacts) {
        Optional<ArtifactRecord> configArtifact = artifacts.stream()
                .filter(a -> a.getType().isConfiguration())
                .findFirst();

        if (configArtifact.isEmpty()) {
            log.info("設定ファイルが提出されていないため既定値を使います runId={}", run.getId());
            return new Resolved(GateConfigDocument.defaults(), null);
        }

        String yaml = read(configArtifact.get());
        GateConfigDocument document = parser.parse(yaml);
        GateConfig stored = store(run, yaml, document);
        return new Resolved(document, stored);
    }

    private GateConfig store(Run run, String yaml, GateConfigDocument document) {
        String hash = sha256(yaml);
        return configs.findByRepositoryIdAndContentHash(run.getRepositoryId(), hash)
                .orElseGet(() -> {
                    int version = configs.findMaxVersion(run.getRepositoryId()) + 1;
                    log.info("新しい設定版を保存します repositoryId={} version={}",
                            run.getRepositoryId(), version);
                    return configs.save(new GateConfig(Uuid7.generate(), run.getRepositoryId(),
                            version, GateConfig.SOURCE_FILE, run.getCommitSha(), hash, yaml,
                            objectMapper.writeValueAsString(document)));
                });
    }

    private String read(ArtifactRecord artifact) {
        if (artifact.getSizeBytes() > MAX_CONFIG_BYTES) {
            throw new ConfigValidationException(List.of(ConfigValidationError.at(null, "",
                    "設定ファイルが大きすぎます（上限 %dKB）".formatted(MAX_CONFIG_BYTES / 1024))));
        }
        try (InputStream in = artifactStore.open(artifact.getStorageKey())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigValidationException(List.of(ConfigValidationError.at(null, "",
                    "設定ファイルを読み出せませんでした: " + e.getMessage())));
        }
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }

    /**
     * @param gateConfig 保存された設定版。既定値を使った場合は null
     */
    public record Resolved(GateConfigDocument document, GateConfig gateConfig) {

        public boolean isDefault() {
            return gateConfig == null;
        }
    }
}
