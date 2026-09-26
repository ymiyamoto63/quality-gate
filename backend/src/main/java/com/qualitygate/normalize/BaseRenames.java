package com.qualitygate.normalize;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.platform.storage.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 収集ランナーが送るファイルの移動・リネーム（{@code git-renames}。{@code collector/bin/renames.sh} の出力）から、
 * 比較元のコミット（{@code baseCommitSha}）から Run のコミットまでの移動を読む（指標仕様書 0.4）。
 *
 * <p>求めるのは比較元からの移動だけ（git の差分そのもの）。比較対象 Run が比較元コミットの Run でない場合
 * （比較元コミットの Run が無く、同じブランチの直前の Run と比べる場合）は移動を追わず、
 * 移動しただけの違反も新規になる。
 */
@Component
public class BaseRenames {

    private static final Logger log = LoggerFactory.getLogger(BaseRenames.class);

    private final ArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    public BaseRenames(ArtifactStore artifactStore, ObjectMapper objectMapper) {
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
    }

    /** @return 新しいパス → 移動前のパス。比較元が無い、成果物が無い・読めない場合は空 */
    public Map<String, String> resolve(Run run, List<ArtifactRecord> artifacts) {
        String base = run.getBaseCommitSha();
        if (base == null || base.equals(run.getCommitSha())) {
            return Map.of();
        }
        Optional<ArtifactRecord> artifact = artifacts.stream()
                .filter(a -> a.getType() == ArtifactType.GIT_RENAMES && a.getDeletedAt() == null)
                .reduce((first, second) -> second);
        if (artifact.isEmpty()) {
            return Map.of();
        }
        try (InputStream in = artifactStore.open(artifact.get().getStorageKey())) {
            return renamesFrom(objectMapper.readTree(in), base);
        } catch (IOException | JacksonException e) {
            log.warn("ファイルの移動を読めませんでした。移動は追跡せずに判定します artifactId={} 理由={}",
                    artifact.get().getId(), e.getMessage());
            return Map.of();
        }
    }

    /** 成果物の比較元が {@code base} と一致するときの移動。一致しなければ空。 */
    static Map<String, String> renamesFrom(JsonNode document, String base) {
        JsonNode node = document.path("base");
        if (!base.equals(node.path("sha").asString(""))) {
            return Map.of();
        }
        Map<String, String> pairs = new LinkedHashMap<>();
        node.path("renames").properties().forEach(entry -> {
            String previous = entry.getValue().asString("");
            if (!entry.getKey().isBlank() && !previous.isBlank()) {
                pairs.put(entry.getKey(), previous);
            }
        });
        return Map.copyOf(pairs);
    }
}
