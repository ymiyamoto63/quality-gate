package com.qualitygate.query;

import com.qualitygate.domain.entity.GateConfig;
import com.qualitygate.domain.repo.GateConfigRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * データの鮮度の基準（FR-06-2 / FR-06-3）。
 *
 * <p>完全計測の間隔はリポジトリの設定（{@code execution.full_measurement_interval_days}）に
 * 従う。基準をサーバが持ち、判定結果をブール値で返すのは、設定を変えたときに
 * 画面の警告が確実に追従するようにするためである。
 */
@Component
public class FreshnessPolicy {

    /** 計測途絶とみなす閾値（FR-06-2）。 */
    public static final Duration STALE_MEASUREMENT = Duration.ofHours(48);
    /** 設定が無い場合の完全計測の間隔（FR-06-3）。 */
    public static final int DEFAULT_FULL_INTERVAL_DAYS = 7;

    private final GateConfigRepository configs;
    private final ObjectMapper objectMapper;

    public FreshnessPolicy(GateConfigRepository configs, ObjectMapper objectMapper) {
        this.configs = configs;
        this.objectMapper = objectMapper;
    }

    /** 最新の設定版が定める完全計測の間隔（日）。 */
    public int fullIntervalDays(UUID repositoryId) {
        return configs.findFirstByRepositoryIdOrderByVersionDesc(repositoryId)
                .map(this::intervalOf)
                .orElse(DEFAULT_FULL_INTERVAL_DAYS);
    }

    private int intervalOf(GateConfig config) {
        JsonNode interval = objectMapper.readTree(config.getParsed())
                .path("execution").path("fullMeasurementIntervalDays");
        return interval.isInt() && interval.asInt() > 0
                ? interval.asInt()
                : DEFAULT_FULL_INTERVAL_DAYS;
    }

    /**
     * 未計測（null）は「古い」とはみなさない。
     * 一度も測っていないリポジトリと、測っていたのに途絶えたリポジトリは別の状態である。
     */
    public static boolean isStale(Instant last, Instant now, Duration threshold) {
        return last != null && last.isBefore(now.minus(threshold));
    }
}
