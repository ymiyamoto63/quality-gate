package com.qualitygate.platform.storage;

import com.qualitygate.platform.config.QualityGateProperties;
import com.qualitygate.platform.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileArtifactStoreTest {

    @TempDir
    Path tempDir;

    private LocalFileArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new LocalFileArtifactStore(
                new QualityGateProperties(tempDir, 0, 0, null, null, null));
    }

    @Test
    void 保存するとサイズとSHA256を返す() {
        StoredArtifact stored = store.store("run-1", "jacoco.xml", content("<report/>"));

        assertThat(stored.sizeBytes()).isEqualTo(9);
        // echo -n '<report/>' | sha256sum
        assertThat(stored.sha256()).hasSize(64);
        assertThat(store.exists(stored.storageKey())).isTrue();
    }

    @Test
    void 保存した内容をそのまま読み出せる() throws Exception {
        StoredArtifact stored = store.store("run-1", "lcov.info", content("SF:src/main.ts"));

        try (InputStream in = store.open(stored.storageKey())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("SF:src/main.ts");
        }
    }

    @Test
    void 削除するとファイル実体が消える() {
        StoredArtifact stored = store.store("run-1", "trivy.sarif", content("{}"));

        store.delete(stored.storageKey());

        assertThat(store.exists(stored.storageKey())).isFalse();
    }

    @Test
    void 存在しないキーの読み出しは業務例外になる() {
        assertThatThrownBy(() -> store.open("run-1/missing.xml"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ファイル実体がありません");
    }

    @Test
    void ファイル名の相対パス指定でルート外に書き込めない() {
        // ファイル名は CI から与えられる。../ を含んでいてもルート配下に収まること。
        StoredArtifact stored = store.store("run-1", "../../etc/passwd", content("x"));

        Path written = tempDir.resolve(stored.storageKey()).normalize();
        assertThat(written).startsWith(tempDir);
        assertThat(Files.exists(written)).isTrue();
    }

    private static InputStream content(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
