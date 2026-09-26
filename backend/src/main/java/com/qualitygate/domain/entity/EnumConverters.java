package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.ArtifactType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * ハイフンを含む表記を持つ列挙型の JPA 変換。
 *
 * <p>DB には CHECK 制約と同じ表記（{@code self-hosted} など）で保存する。
 * 序数（ordinal）は使わない。値の追加で既存データの意味が変わるためである。
 */
public final class EnumConverters {

    private EnumConverters() {
    }

    @Converter(autoApply = true)
    public static class ArtifactTypeConverter implements AttributeConverter<ArtifactType, String> {
        @Override
        public String convertToDatabaseColumn(ArtifactType attribute) {
            return attribute == null ? null : attribute.wire();
        }

        @Override
        public ArtifactType convertToEntityAttribute(String dbData) {
            return dbData == null ? null : ArtifactType.fromWire(dbData);
        }
    }
}
