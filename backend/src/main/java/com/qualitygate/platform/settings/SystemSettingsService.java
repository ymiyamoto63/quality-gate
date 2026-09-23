package com.qualitygate.platform.settings;

import com.qualitygate.domain.entity.SystemSetting;
import com.qualitygate.domain.repo.SystemSettingRepository;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** システム設定の読み書き。値が無ければ既定値を返す。 */
@Service
public class SystemSettingsService {

    static final String RETENTION = "retention";

    private final SystemSettingRepository settings;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public SystemSettingsService(SystemSettingRepository settings, AuditLogger auditLogger,
                                 ObjectMapper objectMapper) {
        this.settings = settings;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RetentionSettings retention() {
        return settings.findById(RETENTION)
                .map(s -> objectMapper.readValue(s.getValue(), RetentionSettings.class))
                .orElse(RetentionSettings.DEFAULTS);
    }

    @Transactional
    public RetentionSettings updateRetention(Actor actor, RetentionSettings value) {
        RetentionSettings before = retention();
        String json = objectMapper.writeValueAsString(value);
        settings.findById(RETENTION).ifPresentOrElse(
                s -> s.update(json, actor.userId()),
                () -> settings.save(new SystemSetting(RETENTION, json, actor.userId())));
        auditLogger.record(actor, AuditAction.RETENTION_SETTINGS_UPDATED, "SYSTEM", RETENTION,
                before, value);
        return value;
    }
}
