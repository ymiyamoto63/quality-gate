package com.qualitygate.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 計測を実行したランナーの種別。
 *
 * <p>GitHub ホストランナーは計算資源を共有しており実行ごとに性能が変動するため、
 * そこで計測した性能値は絶対値しきい値による判定に用いない
 * （{@link MeasurementStatus#REFERENCE}）。
 */
public enum RunnerType implements WireValued {

    SELF_HOSTED("self-hosted"),
    GITHUB_HOSTED("github-hosted");

    private final String wire;

    RunnerType(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RunnerType fromWire(String wire) {
        return WireValued.fromWire(RunnerType.class, wire);
    }

    /** 性能指標を判定に用いてよい（計測条件を統制できる）環境か。 */
    public boolean allowsPerformanceJudgement() {
        return this == SELF_HOSTED;
    }
}
