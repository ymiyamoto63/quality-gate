package com.qualitygate.platform.audit;

import com.qualitygate.domain.entity.AuditLog;
import com.qualitygate.domain.repo.AuditLogRepository;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * 監査ログを書く唯一の入口。
 *
 * <p>呼び出し元の業務トランザクションに参加する（{@link Propagation#MANDATORY}）。
 * 操作が巻き戻れば記録も巻き戻り、操作が確定すれば記録も必ず残る。
 * 別トランザクションで書くと、失敗した操作の記録だけが残ったり、
 * 成功した操作の記録が失われたりする。
 */
@Service
public class AuditLogger {

    private final AuditLogRepository logs;
    private final ObjectMapper objectMapper;

    public AuditLogger(AuditLogRepository logs, ObjectMapper objectMapper) {
        this.logs = logs;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, AuditAction action, String targetType, Object targetId,
                       Object before, Object after) {
        logs.save(new AuditLog(Uuid7.generate(),
                actor.userId(), actor.login(), action.name(), targetType,
                targetId == null ? null : targetId.toString(),
                toJson(before), toJson(after), actor.clientIp(), Instant.now()));
    }

    private String toJson(Object value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }
}
