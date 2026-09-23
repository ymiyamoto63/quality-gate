package com.qualitygate.notify;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.model.JobType;
import com.qualitygate.job.JobHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** 判定完了後の通知（{@code SEND_NOTIFICATION}）。判定とは別のジョブで、失敗しても判定を巻き戻さない。 */
@Component
public class SendNotificationJobHandler implements JobHandler {

    private final NotificationService notifications;
    private final ObjectMapper objectMapper;

    public SendNotificationJobHandler(NotificationService notifications, ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.SEND_NOTIFICATION;
    }

    @Override
    public void handle(Job job) {
        JsonNode payload = objectMapper.readTree(job.getPayload());
        UUID runId = UUID.fromString(payload.path("runId").asString());
        notifications.notifyVerdict(runId, payload.path("evaluationKey").asString(runId.toString()));
    }
}
