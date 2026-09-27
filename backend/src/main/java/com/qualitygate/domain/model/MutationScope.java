package com.qualitygate.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * ミューテーションテストの実行範囲（docs/metrics.md M-02）。
 *
 * <p>収集ランナーは常に全量を計測する。実行範囲は計測条件（{@code variant}）として値に添え、
 * 前回比とトレンドの系列を分ける軸にする（範囲の違う値を比べないため）。
 */
public enum MutationScope implements WireValued {

    /** 全クラス。 */
    ALL("all", "全量");

    /** 成果物のメタデータで実行範囲を表すキー。 */
    public static final String METADATA_KEY = "mutationScope";

    private final String wire;
    private final String label;

    MutationScope(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    @Override
    public String wire() {
        return wire;
    }

    public String label() {
        return label;
    }

    /** 未知の値は空を返す。受け付けるかどうかは呼び出し側が決める。 */
    public static Optional<MutationScope> find(String wire) {
        return Arrays.stream(values()).filter(s -> s.wire.equals(wire)).findFirst();
    }
}
