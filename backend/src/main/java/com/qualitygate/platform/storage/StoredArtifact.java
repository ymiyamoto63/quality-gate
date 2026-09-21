package com.qualitygate.platform.storage;

/**
 * @param storageKey ストア内での位置を示すキー
 * @param sizeBytes  保存されたサイズ
 * @param sha256     内容の SHA-256（16 進小文字）
 */
public record StoredArtifact(String storageKey, long sizeBytes, String sha256) {
}
