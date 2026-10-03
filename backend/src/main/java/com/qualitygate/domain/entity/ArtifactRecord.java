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

/** 取り込んだ成果物のメタデータ。実体は ArtifactStore（ローカルファイル）に置く。 */
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    protected ArtifactRecord() {
    }

    public ArtifactRecord(UUID id, UUID runId, ArtifactType type, String filename,
                          long sizeBytes, String sha256, String storageKey,
                          String componentName, String metadata) {
        this.id = id;
        this.runId = runId;
        this.type = type;
        this.filename = filename;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.componentName = componentName;
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

    public String getMetadata() {
        return metadata;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
