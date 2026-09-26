package com.qualitygate.normalize;

import com.qualitygate.domain.entity.ArtifactRecord;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 収集ランナーが送るファイルの移動・リネーム（{@code git-renames}。{@code collector/bin/renames.sh} の出力）から、
 * 比較元のコミットから Run のコミットまでの移動を求める（指標仕様書 0.4）。
 *
 * <p>比較元（{@code base}）からの移動は git の差分そのものを使う。それ以外のコミット（比較対象 Run のコミット）からの
 * 移動は、first-parent の履歴のコミットごとの移動をつなげて求める。履歴に無いコミットからの移動は求めない
 * （取りこぼした移動は、これまでどおり新規として扱われる。判定は止めない）。
 */
@Component
public class RenameHistory {

    private static final Logger log = LoggerFactory.getLogger(RenameHistory.class);

    private final ArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    public RenameHistory(ArtifactStore artifactStore, ObjectMapper objectMapper) {
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
    }

    /**
     * @param fromCommits 比較元のコミット（比較元コミット、比較対象 Run のコミットなど）
     * @return 新しいパス → 移動前のパス。成果物が無いか読めない場合は空
     *         （空の対応表とは区別する。空の Optional なら次の再評価で求め直す）
     */
    public Optional<Map<String, String>> resolve(List<ArtifactRecord> artifacts, Collection<String> fromCommits) {
        Optional<ArtifactRecord> artifact = artifacts.stream()
                .filter(a -> a.getType() == ArtifactType.GIT_RENAMES && a.getDeletedAt() == null)
                .reduce((first, second) -> second);
        if (artifact.isEmpty()) {
            return Optional.empty();
        }
        JsonNode document;
        try (InputStream in = artifactStore.open(artifact.get().getStorageKey())) {
            document = objectMapper.readTree(in);
        } catch (IOException | JacksonException e) {
            log.warn("ファイルの移動を読めませんでした。移動は追跡せずに判定します artifactId={} 理由={}",
                    artifact.get().getId(), e.getMessage());
            return Optional.empty();
        }
        Map<String, String> renames = new LinkedHashMap<>();
        for (String from : fromCommits) {
            renamesSince(document, from).forEach(renames::putIfAbsent);
        }
        return Optional.of(Map.copyOf(renames));
    }

    /** {@code from} から成果物の {@code head} までの移動（新しいパス → {@code from} でのパス）。求められなければ空。 */
    static Map<String, String> renamesSince(JsonNode document, String from) {
        JsonNode base = document.path("base");
        if (from.equals(base.path("sha").asString(""))) {
            return pairs(base.path("renames"));
        }
        // history は新しい順。from より新しいコミットの移動を、古い順につなげる
        List<JsonNode> newer = new ArrayList<>();
        for (JsonNode commit : document.path("history")) {
            if (from.equals(commit.path("sha").asString(""))) {
                Map<String, String> chained = new HashMap<>();
                for (int i = newer.size() - 1; i >= 0; i--) {
                    chain(chained, pairs(newer.get(i).path("renames")));
                }
                chained.entrySet().removeIf(e -> e.getKey().equals(e.getValue()));
                return Map.copyOf(chained);
            }
            newer.add(commit);
        }
        log.info("比較元のコミットが移動の履歴にありません。このコミットからの移動は追跡しません commit={}", from);
        return Map.of();
    }

    /**
     * 1 コミット分の移動をつなげる。同じコミットの中の入れ替え（a → b と b → a）も正しく扱えるよう、
     * 移動前のパスをすべて引いてから書き換える。
     */
    static void chain(Map<String, String> chained, Map<String, String> commitRenames) {
        Map<String, String> updates = new HashMap<>();
        commitRenames.forEach((current, previous) -> updates.put(current, chained.getOrDefault(previous, previous)));
        commitRenames.values().forEach(chained::remove);
        chained.putAll(updates);
    }

    private static Map<String, String> pairs(JsonNode node) {
        Map<String, String> pairs = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String previous = entry.getValue().asString("");
            if (!entry.getKey().isBlank() && !previous.isBlank()) {
                pairs.put(entry.getKey(), previous);
            }
        });
        return pairs;
    }
}
