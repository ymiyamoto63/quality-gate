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

class ArtifactStoreTest {

    @TempDir
    Path tempDir;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new ArtifactStore(
                new QualityGateProperties("ymiyamoto63/quality-gate", tempDir, 0, 0, null, null,
                        new QualityGateProperties.Login(null, "test-login-password-0123"), null));
    }

    @Test
    void 保存するとサイズとSHA256を返す() {
        StoredArtifact stored = store.store("run-1", "jacoco.xml", content("<report/>"));

        assertThat(stored.sizeBytes()).isEqualTo(9);
        // echo -n '<report/>' | sha256sum
        assertThat(stored.sha256()).hasSize(64);
        assertThat(tempDir.resolve(stored.storageKey())).exists();
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

        assertThat(tempDir.resolve(stored.storageKey())).doesNotExist();
    }

    @Test
    void 存在しないキーの読み出しは失敗する() {
        // 判定の途中で起きれば、Run は処理失敗として記録される
        assertThatThrownBy(() -> store.open("run-1/missing.xml"))
                .isInstanceOf(IllegalStateException.class)
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
