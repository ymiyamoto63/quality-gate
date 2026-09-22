package com.qualitygate.evaluate;

import java.util.List;

/**
 * 指標ごとの判定。指標の追加は実装クラスの追加のみで完結する。
 *
 * <p>評価器は正規化モデルだけを入力とする。ツールを差し替えても判定ロジックは
 * 変わらない（この規則は ModuleDependencyTest が検証する）。
 */
public interface MetricEvaluator {

    String metricId();

    /** コンポーネントごとに複数の結果を返しうる（M-01 は backend / frontend を別に判定する）。 */
    List<MetricResult> evaluate(EvaluationContext context);
}
