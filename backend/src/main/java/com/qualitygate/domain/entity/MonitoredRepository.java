package com.qualitygate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 計測対象リポジトリ。Spring Data の Repository と名前が衝突しないよう Monitored を冠する。
 *
 * <p>画面からは登録しない。収集ランナーが初めて計測を送ったときに作られる。
 * 計測の対象と既定ブランチは計測プロファイル（{@code collector/targets/<owner>__<name>.env}）だけで決める。
 */
@Entity
@Table(name = "repositories")
public class MonitoredRepository {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    /** 計測プロファイルの DEFAULT_BRANCH。トレンドの既定の系列に使う。計測のたびに送られた値で更新する。 */
    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MonitoredRepository() {
    }

    public MonitoredRepository(UUID id, String owner, String name, String defaultBranch) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.defaultBranch = defaultBranch;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }
}
