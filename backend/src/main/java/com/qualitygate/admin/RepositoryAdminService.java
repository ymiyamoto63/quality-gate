package com.qualitygate.admin;

import com.qualitygate.admin.dto.RepositoryRequests.CreateRepositoryRequest;
import com.qualitygate.admin.dto.RepositoryRequests.UpdateRepositoryRequest;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.security.Actor;
import com.qualitygate.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * リポジトリの登録と設定の変更（S-07 / FR-01）。
 * 変更はすべて監査ログに残す（FR-11-1）。
 */
@Service
public class RepositoryAdminService {

    private static final String TARGET_REPOSITORY = "REPOSITORY";

    private final MonitoredRepositoryRepository repositories;
    private final CurrentUser currentUser;
    private final AuditLogger auditLogger;

    public RepositoryAdminService(MonitoredRepositoryRepository repositories,
                                  CurrentUser currentUser,
                                  AuditLogger auditLogger) {
        this.repositories = repositories;
        this.currentUser = currentUser;
        this.auditLogger = auditLogger;
    }

    @Transactional
    public MonitoredRepository create(CreateRepositoryRequest request) {
        Actor actor = currentUser.actor();
        // GitHub の owner / name は大文字小文字を区別しない。別物として登録させない
        if (repositories.findByOwnerIgnoreCaseAndNameIgnoreCase(request.owner(), request.name())
                .isPresent()) {
            throw new ApiException(ErrorCode.REPOSITORY_ALREADY_EXISTS,
                    "%s/%s は既に登録されています".formatted(request.owner(), request.name()));
        }
        MonitoredRepository repository = new MonitoredRepository(Uuid7.generate(),
                request.owner(), request.name(), actor.userId());
        if (request.defaultBranch() != null && !request.defaultBranch().isBlank()) {
            repository.setDefaultBranch(request.defaultBranch().strip());
        }
        repositories.save(repository);
        auditLogger.record(actor, AuditAction.REPOSITORY_CREATED, TARGET_REPOSITORY,
                repository.getId(), null, snapshot(repository));
        return repository;
    }

    @Transactional
    public MonitoredRepository update(UUID repositoryId, UpdateRepositoryRequest request) {
        Actor actor = currentUser.actor();
        MonitoredRepository repository = load(repositoryId);
        Map<String, Object> before = snapshot(repository);
        if (request.defaultBranch() != null) {
            repository.setDefaultBranch(request.defaultBranch().strip());
        }
        if (request.enabled() != null) {
            repository.setEnabled(request.enabled());
        }
        Map<String, Object> after = snapshot(repository);
        if (!before.equals(after)) {
            auditLogger.record(actor, AuditAction.REPOSITORY_UPDATED, TARGET_REPOSITORY,
                    repositoryId, before, after);
        }
        return repository;
    }

    private MonitoredRepository load(UUID repositoryId) {
        return repositories.findById(repositoryId)
                .orElseThrow(() -> ApiException.notFound("リポジトリ", repositoryId));
    }

    private static Map<String, Object> snapshot(MonitoredRepository repository) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fullName", repository.fullName());
        values.put("defaultBranch", repository.getDefaultBranch());
        values.put("enabled", repository.isEnabled());
        return values;
    }
}
