package com.qualitygate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 取り込み用トークン。平文は発行時に 1 度だけ表示し、以後はハッシュのみ保持する。
 *
 * <p>{@code tokenPrefix} は検索用であり秘密ではない。ハッシュで検索すると
 * 全件走査になるうえ、比較時間から情報が漏れうるため、prefix で引いてから
 * 秘密部分を定数時間比較する。
 */
@Entity
@Table(name = "ingest_tokens")
public class IngestToken {

    @Id
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "token_prefix", nullable = false, unique = true, length = 8)
    private String tokenPrefix;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column
    private String description;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected IngestToken() {
    }

    public IngestToken(UUID id, UUID repositoryId, String tokenPrefix, String tokenHash,
                       String description, UUID createdBy) {
        this.id = id;
        this.repositoryId = repositoryId;
        this.tokenPrefix = tokenPrefix;
        this.tokenHash = tokenHash;
        this.description = description;
        this.createdBy = createdBy;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke(Instant at) {
        this.revokedAt = at;
    }

    public void markUsed(Instant at) {
        this.lastUsedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
