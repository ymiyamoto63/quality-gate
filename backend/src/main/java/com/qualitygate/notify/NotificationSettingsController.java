package com.qualitygate.notify;

import com.qualitygate.domain.entity.NotificationSettings;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.NotificationSettingsRepository;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.security.Actor;
import com.qualitygate.platform.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 通知設定（S-09 通知設定、FR-11-2）。管理者のみ。 */
@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/notification-settings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Notifications", description = "通知条件とメールの宛先（管理者のみ）")
public class NotificationSettingsController {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final NotificationSettingsRepository settings;
    private final MonitoredRepositoryRepository repositories;
    private final EmailSender email;
    private final CurrentUser currentUser;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public NotificationSettingsController(NotificationSettingsRepository settings,
                                          MonitoredRepositoryRepository repositories,
                                          EmailSender email, CurrentUser currentUser,
                                          AuditLogger auditLogger, ObjectMapper objectMapper) {
        this.settings = settings;
        this.repositories = repositories;
        this.email = email;
        this.currentUser = currentUser;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @Operation(summary = "通知設定を取得する")
    @Transactional(readOnly = true)
    public NotificationSettingsDtos.NotificationSettingsResponse get(@PathVariable UUID repositoryId) {
        requireRepository(repositoryId);
        return toResponse(settings.findById(repositoryId)
                .orElseGet(() -> new NotificationSettings(repositoryId)));
    }

    @PutMapping
    @Operation(summary = "通知設定を更新する", description = "変更は監査ログに残す。")
    @Transactional
    public NotificationSettingsDtos.NotificationSettingsResponse update(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody NotificationSettingsDtos.UpdateNotificationSettingsRequest request) {
        Actor actor = currentUser.actor();
        requireRepository(repositoryId);
        NotificationSettings current = settings.findById(repositoryId)
                .orElseGet(() -> settings.save(new NotificationSettings(repositoryId)));
        Map<String, Object> before = auditView(current);

        if (request.condition() != null) {
            current.setCondition(request.condition());
        }
        if (request.emailRecipients() != null) {
            current.setEmailRecipients(objectMapper.writeValueAsString(
                    request.emailRecipients().stream().map(String::strip).distinct().toList()));
        }
        current.touch(actor.userId());
        auditLogger.record(actor, AuditAction.NOTIFICATION_SETTINGS_UPDATED, "REPOSITORY",
                repositoryId, before, auditView(current));
        return toResponse(current);
    }

    private NotificationSettingsDtos.NotificationSettingsResponse toResponse(NotificationSettings s) {
        return new NotificationSettingsDtos.NotificationSettingsResponse(s.getCondition(),
                objectMapper.readValue(s.getEmailRecipients(), STRING_LIST),
                email.isConfigured(), s.getUpdatedAt());
    }

    private Map<String, Object> auditView(NotificationSettings s) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("condition", s.getCondition().name());
        view.put("emailRecipients", objectMapper.readValue(s.getEmailRecipients(), STRING_LIST));
        return view;
    }

    private void requireRepository(UUID repositoryId) {
        if (!repositories.existsById(repositoryId)) {
            throw ApiException.notFound("リポジトリ", repositoryId);
        }
    }
}
