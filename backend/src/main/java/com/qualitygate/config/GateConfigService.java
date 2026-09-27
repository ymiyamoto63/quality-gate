package com.qualitygate.config;

import com.qualitygate.domain.gate.ConfigValidationError;
import com.qualitygate.domain.gate.ConfigValidationException;
import com.qualitygate.domain.gate.GateConfigDocument;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.platform.storage.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 判定に使う設定を解決する。
 *
 * <p>設定は収集ランナーが {@code quality-gate-config} 型の成果物として Run ごとに送る
 * （{@code collector/targets/<owner>__<name>.gate.yml}。Git で管理する唯一の置き場所。DD-13）。
 * 設定は Run の成果物として残るため、再評価でも同じ設定で判定できる。版の履歴は Git で見る。
 */
@Service
public class GateConfigService {

    private static final Logger log = LoggerFactory.getLogger(GateConfigService.class);
    private static final int MAX_CONFIG_BYTES = 256 * 1024;

    private final GateConfigParser parser;
    private final ArtifactStore artifactStore;

    public GateConfigService(GateConfigParser parser, ArtifactStore artifactStore) {
        this.parser = parser;
        this.artifactStore = artifactStore;
    }

    /**
     * Run とともに送られた設定を読む。設定ファイルが無ければ既定値を使う
     * （収集ランナーは設定ファイルが無ければ送信の前に止まるため、既定値になるのは手動で送った Run だけ）。
     * 検証エラーは {@link ConfigValidationException} として投げる。
     * 不正な設定で判定を続けると、意図しないしきい値で合格が出てしまう。
     */
    public GateConfigDocument resolve(Run run, List<ArtifactRecord> artifacts) {
        return find(artifacts).orElseGet(() -> {
            log.info("設定ファイルが提出されていないため既定値を使います runId={}", run.getId());
            return GateConfigDocument.defaults();
        });
    }

    /** Run とともに送られた設定。設定ファイルが無ければ空。 */
    public Optional<GateConfigDocument> find(List<ArtifactRecord> artifacts) {
        return artifacts.stream()
                .filter(a -> a.getType().isConfiguration() && a.getDeletedAt() == null)
                .findFirst()
                .map(artifact -> parser.parse(read(artifact)));
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
}
