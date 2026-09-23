package com.qualitygate.platform.storage;

import java.io.InputStream;

/**
 * 取り込んだ成果物の保管先を抽象化する。
 *
 * <p>実装はローカルファイルシステム（{@link LocalFileArtifactStore}）。
 * 対象 1 リポジトリ・10GB 規模ではオブジェクトストレージは過剰であるため
 * （docs/04-tech-stack.md 5.2）。将来 S3 互換へ移す場合は実装の追加のみで済む。
 */
public interface ArtifactStore {

    /**
     * 成果物を保存し、保存キーとハッシュ・サイズを返す。
     * ストリームは呼び出し側が閉じる。
     */
    StoredArtifact store(String runId, String filename, InputStream content);

    /** 保存キーから読み出す。呼び出し側が閉じる。 */
    InputStream open(String storageKey);

    /** ファイル実体を削除する。メタデータの削除は呼び出し側の責務。 */
    void delete(String storageKey);

    /**
     * 指定時刻より前に書き込まれた保存キーを列挙する。孤児ファイル
     * （DB に記録の無いファイル）の回収に使う（docs/05-architecture.md 3.3）。
     */
    java.util.List<String> listKeysWrittenBefore(java.time.Instant before, int limit);

    boolean exists(String storageKey);
}
