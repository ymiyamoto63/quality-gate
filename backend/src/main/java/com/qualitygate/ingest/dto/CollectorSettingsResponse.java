package com.qualitygate.ingest.dto;

import java.util.List;

/**
 * 収集ランナーが計測の前に読む設定。
 *
 * @param repository      計測対象のリポジトリ（{@code QG_REPOSITORY}）
 * @param disabledMetrics 判定しない指標（{@code QG_DISABLED_METRICS}）。収集ランナーはこれを計測しない
 */
public record CollectorSettingsResponse(String repository, List<String> disabledMetrics) {
}
