package com.qualitygate.domain.repo;

import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunnerType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * トレンド 1 点分の生データ。
 *
 * <p>{@code measurements} と {@code runs} を結合して取り出す。判定は行わず、
 * 判定時に確定した値をそのまま運ぶ。
 *
 * @param variant 計測条件（M-02 の実行範囲など）。条件の区別が無い指標では {@code null}
 * @param value 実測値。未計測は {@code null}。<strong>0 で代用しない</strong>
 */
public record TrendRow(
        UUID runId,
        Instant measuredAt,
        String componentName,
        String variant,
        RunnerType runnerType,
        MeasurementStatus status,
        BigDecimal value,
        String unit,
        String threshold,
        String commitSha) {
}
