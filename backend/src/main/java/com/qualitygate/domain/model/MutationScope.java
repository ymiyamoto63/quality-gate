package com.qualitygate.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * ミューテーションテストの実行範囲（docs/initial/02-metrics-spec.md M-02）。
 *
 * <p>変更範囲に限った値と全量の値は比較できない。変更したクラスだけを
 * 測れば、よくテストされた既存コードが分母から抜けるためである。
 * そのため実行範囲は計測条件（{@code variant}）として値に添え、
 * 前回比とトレンドの系列を分ける軸にする。
 */
public enum MutationScope implements WireValued {

    /** {@code baseCommitSha} からの変更クラスのみ。PR の既定。 */
    CHANGED("changed", "変更範囲"),
    /** 全クラス。夜間・週次の全量計測。 */
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
