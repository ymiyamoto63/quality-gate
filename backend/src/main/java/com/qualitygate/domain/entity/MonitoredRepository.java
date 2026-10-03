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
 * <p>画面からは登録しない。計測対象（{@code QG_REPOSITORY}）の計測が初めて届いたときに作られる。
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MonitoredRepository() {
    }

    public MonitoredRepository(UUID id, String owner, String name) {
        this.id = id;
        this.owner = owner;
        this.name = name;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
