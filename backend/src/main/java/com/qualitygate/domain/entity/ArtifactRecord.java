package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.ArtifactType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 取り込んだ成果物のメタデータ。
 *
 * <p>{@code deletedAt} はファイル実体を削除したことを表す。メタデータは残すため、
 * 保持期間を過ぎても「このとき何を取り込んだか」は追える。
 */
@Entity
@Table(name = "artifacts")
public class ArtifactRecord {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(nullable = false)
    private ArtifactType type;

    @Column(nullable = false)
    private String filename;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "component_name")
    private String componentName;

    @Column
    private String scope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "parse_status", nullable = false)
    private String parseStatus = "PENDING";

    @Column(name = "parse_error")
    private String parseError;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ArtifactRecord() {
    }

    public ArtifactRecord(UUID id, UUID runId, ArtifactType type, String filename,
                          long sizeBytes, String sha256, String storageKey,
                          String componentName, String scope, String metadata) {
        this.id = id;
        this.runId = runId;
        this.type = type;
        this.filename = filename;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.componentName = componentName;
        this.scope = scope;
        this.metadata = metadata;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public ArtifactType getType() {
        return type;
    }

    public String getFilename() {
        return filename;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    /** アップロード時に宣言されたコンポーネント名（backend / frontend）。 */
    public String getComponentName() {
        return componentName;
    }

    /** base / head。M-07 のベース比較に使う。 */
    public String getScope() {
        return scope;
    }

    public String getParseStatus() {
        return parseStatus;
    }

    public void markParsed() {
        this.parseStatus = "OK";
        this.parseError = null;
    }

    public void markParseFailed(String error) {
        this.parseStatus = "FAILED";
        this.parseError = error;
    }

    public String getMetadata() {
        return metadata;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    /** 保持期間を過ぎてファイル実体を消したことを記録する。メタデータは残す。 */
    public void markDeleted(Instant at) {
        this.deletedAt = at;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
