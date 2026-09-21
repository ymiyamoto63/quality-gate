package com.qualitygate.domain.entity;

import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 利用者。許可リストそのものを兼ねる。
 *
 * <p>このテーブルに行が無い GitHub ユーザーはログインできない。
 * Organization を使わないため、これが実質的な入口のアクセス制御になる。
 */
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    private UUID id;

    /** GitHub のユーザー名。変更されうるため識別子としては github_user_id を優先する。 */
    @Column(name = "github_login", nullable = false, unique = true)
    private String githubLogin;

    /** 不変の GitHub ユーザー ID。事前登録時点では未確定のため null を許す。 */
    @Column(name = "github_user_id")
    private Long githubUserId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected UserAccount() {
    }

    public UserAccount(UUID id, String githubLogin, UserRole role, UserStatus status, UUID createdBy) {
        this.id = id;
        this.githubLogin = githubLogin;
        this.role = role;
        this.status = status;
        this.createdBy = createdBy;
    }

    /** OAuth ログイン成功時に GitHub 側の情報を反映する。 */
    public void recordLogin(long githubUserId, String displayName, String avatarUrl, Instant at) {
        this.githubUserId = githubUserId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.lastLoginAt = at;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getGithubLogin() {
        return githubLogin;
    }

    public Long getGithubUserId() {
        return githubUserId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
