package com.qualitygate.admin;

import com.qualitygate.admin.dto.AuditLogListResponse;
import com.qualitygate.domain.entity.AuditLog;
import com.qualitygate.domain.repo.AuditLogRepository;
import com.qualitygate.platform.web.PageCursor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 監査ログ（S-08）。閲覧は管理者のみ、書き込みの API は持たない（追記はサーバ内部のみ）。 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "AuditLogs", description = "監査ログの閲覧（管理者のみ）")
public class AuditLogController {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    /** キーセットの初期値。id の比較で必ず「より小さい」側に入る最大の UUID。 */
    private static final UUID MAX_UUID = new UUID(-1L, -1L);

    private final AuditLogRepository logs;
    private final ObjectMapper objectMapper;

    public AuditLogController(AuditLogRepository logs, ObjectMapper objectMapper) {
        this.logs = logs;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @Operation(summary = "監査ログを新しい順に一覧する")
    @Transactional(readOnly = true)
    public AuditLogListResponse list(
            @Parameter(description = "省略時は to の 30 日前")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "省略時は現在時刻")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "操作種別（REPOSITORY_UPDATED など）")
            @RequestParam(required = false) String action,
            @RequestParam(required = false, defaultValue = "0") int limit,
            @RequestParam(required = false) String cursor) {
        Instant until = to == null ? Instant.now() : to;
        Instant since = from == null ? until.minus(Duration.ofDays(30)) : from;
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        Instant cursorAt = until;
        UUID cursorId = MAX_UUID;
        if (cursor != null && !cursor.isBlank()) {
            PageCursor.Keyset keyset = PageCursor.toKeyset(cursor);
            cursorAt = keyset.measuredAt();
            cursorId = keyset.id();
        }

        List<AuditLog> page = logs.findPage(since, until,
                action == null || action.isBlank() ? null : action.trim(),
                cursorAt, cursorId, PageRequest.of(0, size + 1));
        boolean hasMore = page.size() > size;
        List<AuditLog> items = hasMore ? page.subList(0, size) : page;
        String next = hasMore
                ? PageCursor.ofKeyset(items.getLast().getOccurredAt(), items.getLast().getId())
                : null;
        return new AuditLogListResponse(items.stream().map(this::toItem).toList(), next, hasMore);
    }

    private AuditLogListResponse.AuditLogItem toItem(AuditLog log) {
        return new AuditLogListResponse.AuditLogItem(log.getId(), log.getOccurredAt(),
                log.getActorLogin(), log.getAction(), log.getTargetType(), log.getTargetId(),
                parse(log.getBeforeValue()), parse(log.getAfterValue()));
    }

    private Map<String, Object> parse(String json) {
        return json == null ? null : objectMapper.readValue(json, JSON_OBJECT);
    }
}
