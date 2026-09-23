package com.qualitygate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 計測対象リポジトリ。Spring Data の Repository と名前が衝突しないよう Monitored を冠する。 */
@Entity
@Table(name = "repositories")
public class MonitoredRepository {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch = "main";

    @Column(name = "measure_pull_requests", nullable = false)
    private boolean measurePullRequests = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected MonitoredRepository() {
    }

    public MonitoredRepository(UUID id, String owner, String name, UUID createdBy) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.createdBy = createdBy;
    }

    public String fullName() {
        return owner + "/" + name;
    }

    public UUID getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public boolean isMeasurePullRequests() {
        return measurePullRequests;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
        this.updatedAt = Instant.now();
    }

    public void setMeasurePullRequests(boolean measurePullRequests) {
        this.measurePullRequests = measurePullRequests;
        this.updatedAt = Instant.now();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }
}
