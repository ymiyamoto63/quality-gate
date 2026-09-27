package com.qualitygate.platform.storage;

import com.qualitygate.platform.config.QualityGateProperties;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 取り込んだ成果物をローカルファイルシステムに保存する。
 *
 * <p>対象 1 リポジトリ・10GB 規模ではオブジェクトストレージは過剰であるため（docs/development.md 7 章）。
 */
@Component
public class ArtifactStore {

    private final Path root;

    public ArtifactStore(QualityGateProperties properties) {
        this.root = properties.artifactRoot().toAbsolutePath().normalize();
    }

    /** 成果物を保存し、保存キーとハッシュ・サイズを返す。ストリームは呼び出し側が閉じる。 */
    public StoredArtifact store(String runId, String filename, InputStream content) {
        String key = "%s/%s".formatted(sanitize(runId), sanitize(filename));
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (OutputStream out = Files.newOutputStream(target);
                 DigestOutputStream digesting = new DigestOutputStream(out, digest)) {
                size = content.transferTo(digesting);
            }
            return new StoredArtifact(key, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            throw new IllegalStateException("成果物の保存に失敗しました: " + key, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }

    /** 保存キーから読み出す。呼び出し側が閉じる。 */
    public InputStream open(String storageKey) {
        Path path = resolve(storageKey);
        if (!Files.exists(path)) {
            throw new IllegalStateException("成果物のファイル実体がありません: " + storageKey);
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new IllegalStateException("成果物の読み出しに失敗しました: " + storageKey, e);
        }
    }

    /** ファイル実体を削除する。メタデータの削除は呼び出し側の責務。 */
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new IllegalStateException("成果物の削除に失敗しました: " + storageKey, e);
        }
    }

    /**
     * 保存キーをルート配下の実パスに解決する。
     *
     * <p>ファイル名は外部（CI）から与えられるため、{@code ../} を含むキーで
     * ルート外に書き込まれないことを正規化後に検証する。
     */
    private Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "不正な保存キーです: " + storageKey);
        }
        return path;
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "unnamed" : cleaned;
    }
}
