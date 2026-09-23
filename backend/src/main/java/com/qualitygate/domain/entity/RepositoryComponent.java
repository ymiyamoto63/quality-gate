package com.qualitygate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * リポジトリ内の構成単位（backend / frontend など。FR-01-2）。
 *
 * <p>Spring の {@code @Component} と名前が衝突しないよう Repository を冠する。
 */
@Entity
@Table(name = "components")
public class RepositoryComponent {

    @Id
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String language;

    /** リポジトリ相対の glob の JSON 配列。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "path_patterns", nullable = false, columnDefinition = "jsonb")
    private String pathPatterns;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected RepositoryComponent() {
    }

    public RepositoryComponent(UUID id, UUID repositoryId, String name, String language,
                               String pathPatterns, int displayOrder) {
        this.id = id;
        this.repositoryId = repositoryId;
        this.name = name;
        this.language = language;
        this.pathPatterns = pathPatterns;
        this.displayOrder = displayOrder;
    }

    public void redefine(String language, String pathPatterns) {
        this.language = language;
        this.pathPatterns = pathPatterns;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getName() {
        return name;
    }

    public String getLanguage() {
        return language;
    }

    public String getPathPatterns() {
        return pathPatterns;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
