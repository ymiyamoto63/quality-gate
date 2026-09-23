package com.qualitygate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** システム全体の設定（保持期間など）。キーごとに 1 行、値は JSON。 */
@Entity
@Table(name = "system_settings")
public class SystemSetting {

    @Id
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String value;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SystemSetting() {
    }

    public SystemSetting(String key, String value, UUID updatedBy) {
        this.key = key;
        this.value = value;
        this.updatedBy = updatedBy;
    }

    public void update(String newValue, UUID by) {
        this.value = newValue;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
