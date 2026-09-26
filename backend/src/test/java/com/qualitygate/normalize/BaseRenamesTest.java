package com.qualitygate.normalize;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.platform.storage.ArtifactStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 収集ランナーの collector/bin/renames.sh の出力から、比較元からの移動を読む。 */
class BaseRenamesTest {

    private static final String HEAD = "h".repeat(40);
    private static final String BASE = "b".repeat(40);

    private static final String DOCUMENT = """
            {"head": "%s",
             "base": {"sha": "%s", "renames": {"c.txt": "b.txt", "x/new.java": "x/old.java"}}}
            """.formatted(HEAD, BASE);

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 比較元からの移動は差分をそのまま使う() {
        assertThat(BaseRenames.renamesFrom(objectMapper.readTree(DOCUMENT), BASE))
                .containsOnly(Map.entry("c.txt", "b.txt"), Map.entry("x/new.java", "x/old.java"));
    }

    @Test
    void 成果物の比較元が_Run_の比較元と違えば移動を使わない() {
        assertThat(BaseRenames.renamesFrom(objectMapper.readTree(DOCUMENT), "9".repeat(40))).isEmpty();
    }

    @Test
    void 成果物から比較元からの移動を読む() {
        ArtifactStore store = mock(ArtifactStore.class);
        when(store.open("renames")).thenReturn(new ByteArrayInputStream(DOCUMENT.getBytes(StandardCharsets.UTF_8)));
        ArtifactRecord record = new ArtifactRecord(UUID.randomUUID(), UUID.randomUUID(), ArtifactType.GIT_RENAMES,
                "renames.json", 0, "sha256", "renames", null, null, null);

        assertThat(new BaseRenames(store, objectMapper).resolve(runWithBase(BASE), List.of(record)))
                .containsOnly(Map.entry("c.txt", "b.txt"), Map.entry("x/new.java", "x/old.java"));
    }

    @Test
    void 成果物か比較元が無ければ求めない() {
        BaseRenames renames = new BaseRenames(mock(ArtifactStore.class), objectMapper);
        assertThat(renames.resolve(runWithBase(BASE), List.of())).isEmpty();
        assertThat(renames.resolve(runWithBase(null), List.of())).isEmpty();
    }

    private static Run runWithBase(String base) {
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), HEAD, "main", "collector", Instant.now(), 1);
        run.setBaseCommitSha(base);
        return run;
    }
}
