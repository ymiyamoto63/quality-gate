package com.qualitygate.notify;

import com.qualitygate.domain.entity.GateConfig;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.job.DailyBatchListener;
import com.qualitygate.waiver.WaiverService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 日次バッチの節目で、免除の期限接近と計測の途絶を知らせる。 */
@Component
public class NotificationDailyListener implements DailyBatchListener {

    private final NotificationService notifications;
    private final WaiverService waivers;
    private final MonitoredRepositoryRepository repositories;
    private final GateConfigRepository configs;
    private final ObjectMapper objectMapper;

    public NotificationDailyListener(NotificationService notifications, WaiverService waivers,
                                     MonitoredRepositoryRepository repositories,
                                     GateConfigRepository configs, ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.waivers = waivers;
        this.repositories = repositories;
        this.configs = configs;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onWaiversChecked(Instant now) {
        notifications.notifyWaiversExpiring(waivers.expiringSoon(now));
    }

    @Override
    public void onFreshnessCheck(Instant now) {
        Map<UUID, Integer> intervals = new HashMap<>();
        repositories.findByEnabledTrueOrderByOwnerAscNameAsc().forEach(repository ->
                configs.findFirstByRepositoryIdOrderByVersionDesc(repository.getId())
                        .map(this::intervalOf)
                        .ifPresent(days -> intervals.put(repository.getId(), days)));
        notifications.notifyStaleness(now, intervals);
    }

    private int intervalOf(GateConfig config) {
        JsonNode days = objectMapper.readTree(config.getParsed())
                .path("execution").path("fullMeasurementIntervalDays");
        return days.isInt() && days.asInt() > 0 ? days.asInt() : 7;
    }
}
