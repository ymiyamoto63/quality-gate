package com.qualitygate.domain.model;

import java.util.Arrays;

/**
 * 列挙定数と、API / DB 上の表記（ハイフンを含みうる）を対応づける。
 *
 * <p>{@code self-hosted} や {@code jacoco-xml} のようにハイフンを含む値は
 * Java の識別子にできないため、定数名とは別に表記を持たせる。
 */
public interface WireValued {

    String wire();

    static <E extends Enum<E> & WireValued> E fromWire(Class<E> type, String wire) {
        return Arrays.stream(type.getEnumConstants())
                .filter(e -> e.wire().equals(wire))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "%s に対応する値がありません: %s".formatted(type.getSimpleName(), wire)));
    }
}
