package com.qualitygate.normalize;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.platform.storage.ArtifactStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 収集ランナーの collector/bin/renames.sh の出力から、比較元からの移動を求める。 */
class RenameHistoryTest {

    private static final String HEAD = "h".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String C2 = "2".repeat(40);
    private static final String C1 = "1".repeat(40);

    /** C1 → C2（a.txt → b.txt）→ HEAD（b.txt → c.txt、x/old → x/new）。比較元は C2。 */
    private static final String DOCUMENT = """
            {"head": "%s",
             "base": {"sha": "%s", "renames": {"c.txt": "b.txt", "x/new.java": "x/old.java"}},
             "history": [
               {"sha": "%s", "renames": {"c.txt": "b.txt", "x/new.java": "x/old.java"}},
               {"sha": "%s", "renames": {"b.txt": "a.txt"}},
               {"sha": "%s"}
             ]}
            """.formatted(HEAD, BASE, HEAD, C2, C1);

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 比較元からの移動は差分をそのまま使う() {
        assertThat(RenameHistory.renamesSince(objectMapper.readTree(DOCUMENT), BASE))
                .containsOnly(Map.entry("c.txt", "b.txt"), Map.entry("x/new.java", "x/old.java"));
    }

    @Test
    void 履歴のコミットからの移動はコミットごとの移動をつなげる() {
        assertThat(RenameHistory.renamesSince(objectMapper.readTree(DOCUMENT), C1))
                .containsOnly(Map.entry("c.txt", "a.txt"), Map.entry("x/new.java", "x/old.java"));
        assertThat(RenameHistory.renamesSince(objectMapper.readTree(DOCUMENT), HEAD)).isEmpty();
    }

    @Test
    void 履歴に無いコミットからの移動は求めない() {
        assertThat(RenameHistory.renamesSince(objectMapper.readTree(DOCUMENT), "9".repeat(40))).isEmpty();
    }

    @Test
    void 同じコミットの中の入れ替えと元に戻した移動を扱える() {
        Map<String, String> chained = new HashMap<>();
        RenameHistory.chain(chained, Map.of("b", "a"));
        RenameHistory.chain(chained, Map.of("a", "b", "c", "d"));
        RenameHistory.chain(chained, Map.of("d", "c"));

        assertThat(chained).containsOnly(Map.entry("a", "a"), Map.entry("d", "d"));
    }

    @Test
    void 成果物から比較元ごとの移動をまとめる() {
        ArtifactStore store = mock(ArtifactStore.class);
        when(store.open("renames")).thenReturn(new ByteArrayInputStream(DOCUMENT.getBytes(StandardCharsets.UTF_8)));
        ArtifactRecord record = new ArtifactRecord(UUID.randomUUID(), UUID.randomUUID(), ArtifactType.GIT_RENAMES,
                "renames.json", 0, "sha256", "renames", null, null, null);

        assertThat(new RenameHistory(store, objectMapper).resolve(List.of(record), List.of(BASE, C1)))
                .hasValueSatisfying(renames -> assertThat(renames)
                        .containsOnly(Map.entry("c.txt", "b.txt"), Map.entry("x/new.java", "x/old.java")));
    }

    @Test
    void 成果物が無ければ求めない() {
        assertThat(new RenameHistory(mock(ArtifactStore.class), objectMapper).resolve(List.of(), List.of(BASE)))
                .isEmpty();
    }
}
