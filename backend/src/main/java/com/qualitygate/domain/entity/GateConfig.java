package com.qualitygate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 合格ラインの設定の 1 版。
 *
 * <p>Run はどの版で判定されたかを保持する。後からしきい値を変えても、
 * 過去の Run は「当時の設定でどう判定されたか」を保持し続ける。
 */
@Entity
@Table(name = "gate_configs")
public class GateConfig {

    public static final String SOURCE_FILE = "FILE";
    public static final String SOURCE_UI = "UI";
    public static final String SOURCE_DEFAULT = "DEFAULT";

    @Id
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    /** リポジトリ内の連番。 */
    @Column(nullable = false)
    private int version;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "source_commit_sha", length = 40)
    private String sourceCommitSha;

    /** 内容の SHA-256。同じ内容なら版を増やさないための鍵。 */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "raw_yaml", nullable = false)
    private String rawYaml;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String parsed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected GateConfig() {
    }

    @SuppressWarnings("java:S107")
    public GateConfig(UUID id, UUID repositoryId, int version, String sourceType,
                      String sourceCommitSha, String contentHash, String rawYaml, String parsed) {
        this.id = id;
        this.repositoryId = repositoryId;
        this.version = version;
        this.sourceType = sourceType;
        this.sourceCommitSha = sourceCommitSha;
        this.contentHash = contentHash;
        this.rawYaml = rawYaml;
        this.parsed = parsed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public int getVersion() {
        return version;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceCommitSha() {
        return sourceCommitSha;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getRawYaml() {
        return rawYaml;
    }

    public String getParsed() {
        return parsed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
