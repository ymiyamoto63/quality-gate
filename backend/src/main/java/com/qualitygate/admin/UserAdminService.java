package com.qualitygate.admin;

import com.qualitygate.admin.dto.CreateUserRequest;
import com.qualitygate.admin.dto.UpdateUserRequest;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.security.Actor;
import com.qualitygate.platform.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 許可リスト（利用者）の管理。変更はすべて監査ログに残す（FR-14-1）。 */
@Service
public class UserAdminService {

    private static final String TARGET = "USER";

    private final UserAccountRepository users;
    private final CurrentUser currentUser;
    private final AuditLogger auditLogger;

    public UserAdminService(UserAccountRepository users, CurrentUser currentUser,
                            AuditLogger auditLogger) {
        this.users = users;
        this.currentUser = currentUser;
        this.auditLogger = auditLogger;
    }

    @Transactional(readOnly = true)
    public List<UserAccount> list() {
        return users.findAll(Sort.by("githubLogin"));
    }

    @Transactional
    public UserAccount add(CreateUserRequest request) {
        Actor actor = currentUser.actor();
        if (users.findByGithubLoginIgnoreCase(request.githubLogin()).isPresent()) {
            throw new ApiException(ErrorCode.USER_ALREADY_EXISTS,
                    "%s は既に許可リストにあります".formatted(request.githubLogin()));
        }
        UserRole role = request.role() == null ? UserRole.VIEWER : request.role();
        UserAccount user = users.save(new UserAccount(Uuid7.generate(), request.githubLogin(),
                role, UserStatus.ACTIVE, actor.userId()));
        auditLogger.record(actor, AuditAction.USER_ADDED, TARGET, user.getId(), null,
                Map.of("githubLogin", user.getGithubLogin(), "role", role.name()));
        return user;
    }

    /**
     * ロールの変更と無効化。
     *
     * <p>自分自身を降格・無効化することはできない。最後の管理者がそうすると、
     * 誰も管理操作を行えなくなり、許可リストを DB で直接直すしかなくなる。
     * 同じ理由で、有効な管理者が 0 人になる変更も拒否する。
     */
    @Transactional
    public UserAccount update(UUID userId, UpdateUserRequest request) {
        Actor actor = currentUser.actor();
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("利用者", userId));

        boolean demotes = request.role() != null && request.role() != UserRole.ADMIN;
        boolean disables = request.status() == UserStatus.DISABLED;
        if (user.getId().equals(actor.userId()) && (demotes || disables)) {
            throw new ApiException(ErrorCode.ADMIN_REQUIRED,
                    "自分自身のロールを下げる・無効化することはできません。別の管理者に依頼してください");
        }
        if (user.getRole() == UserRole.ADMIN && user.isActive() && (demotes || disables)
                && activeAdminCount() <= 1) {
            throw new ApiException(ErrorCode.ADMIN_REQUIRED,
                    "有効な管理者がいなくなるため変更できません。先に別の管理者を追加してください");
        }

        if (request.role() != null && request.role() != user.getRole()) {
            UserRole before = user.getRole();
            user.setRole(request.role());
            auditLogger.record(actor, AuditAction.USER_ROLE_CHANGED, TARGET, user.getId(),
                    Map.of("role", before.name()), Map.of("role", request.role().name()));
        }
        if (request.status() != null && request.status() != user.getStatus()) {
            UserStatus before = user.getStatus();
            user.setStatus(request.status());
            auditLogger.record(actor, AuditAction.USER_STATUS_CHANGED, TARGET, user.getId(),
                    Map.of("status", before.name()), Map.of("status", request.status().name()));
        }
        return user;
    }

    private long activeAdminCount() {
        return users.findAll().stream()
                .filter(u -> u.getRole() == UserRole.ADMIN && u.isActive())
                .count();
    }
}
