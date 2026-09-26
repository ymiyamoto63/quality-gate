package com.qualitygate.domain.repo;

import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.Severity;

import java.util.Set;
import java.util.UUID;

/**
 * 違反一覧の検索条件（docs/initial/07-api-design.md 4.3）。
 *
 * <p>空集合は「絞り込まない」を意味する。null と空集合を区別しないのは、
 * フィルタを全部外した状態と指定しない状態を画面上で区別できないためである。
 */
public record FindingCriteria(
        UUID runId,
        Set<String> metricIds,
        Set<FindingState> states,
        Set<Severity> severities) {

    public FindingCriteria {
        metricIds = metricIds == null ? Set.of() : Set.copyOf(metricIds);
        states = states == null ? Set.of() : Set.copyOf(states);
        severities = severities == null ? Set.of() : Set.copyOf(severities);
    }
}
