package com.qualitygate.auth;

import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GitHub の認証結果を許可リストと突き合わせる。
 *
 * <p>Organization を使わないため「組織のメンバーであること」をログイン条件にできない。
 * GitHub アカウントを持つ誰でも OAuth フロー自体は完了できるため、
 * <strong>この許可リストが実質的な入口のアクセス制御</strong>になる。省略できない。
 */
@Service
public class AllowlistOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final Logger log = LoggerFactory.getLogger(AllowlistOAuth2UserService.class);
    private static final String NOT_ALLOWLISTED = "user_not_allowlisted";
    private static final String DISABLED = "user_disabled";

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final UserAccountRepository users;
    private final AuditLogger auditLogger;

    public AllowlistOAuth2UserService(UserAccountRepository users, AuditLogger auditLogger) {
        this.users = users;
        this.auditLogger = auditLogger;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User githubUser = delegate.loadUser(request);
        Map<String, Object> attributes = githubUser.getAttributes();

        String login = String.valueOf(attributes.get("login"));
        long githubUserId = ((Number) attributes.get("id")).longValue();

        UserAccount account = users.findByGithubUserId(githubUserId)
                .or(() -> users.findByGithubLoginIgnoreCase(login))
                .orElseGet(() -> bootstrapOrReject(login));

        if (!account.isActive()) {
            throw new OAuth2AuthenticationException(new OAuth2Error(DISABLED,
                    "アカウントが無効です。管理者にお問い合わせください。", null));
        }

        account.recordLogin(githubUserId,
                Optional.ofNullable(attributes.get("name")).map(String::valueOf).orElse(login),
                Optional.ofNullable(attributes.get("avatar_url")).map(String::valueOf).orElse(null),
                Instant.now());

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority(account.getRole().authority())),
                attributes, "login");
    }

    /**
     * 利用者が 1 件も存在しない初期状態に限り、最初にログインしたユーザーを管理者として登録する。
     *
     * <p>構築直後に誰もログインできない状態を避けるための措置であり、
     * <strong>1 件でも利用者が存在すれば二度と発動しない</strong>。
     * 不特定のユーザーが管理者になる経路を残さないためである。
     */
    private UserAccount bootstrapOrReject(String login) {
        if (users.count() > 0) {
            log.warn("許可リストに未登録のログイン試行: login={}", login);
            throw new OAuth2AuthenticationException(new OAuth2Error(NOT_ALLOWLISTED,
                    "許可リストに登録されていません。管理者に登録を依頼してください。", null));
        }
        log.warn("初期セットアップ: 最初のログインユーザーを管理者として登録します login={}", login);
        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), login,
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        // 管理者が生まれた経路を必ず残す。後から「誰がいつ管理者になったか」を辿れるように
        auditLogger.record(new Actor(admin.getId(), login, null), AuditAction.BOOTSTRAP_ADMIN,
                "USER", admin.getId(), null, java.util.Map.of("githubLogin", login, "role", "ADMIN"));
        return admin;
    }
}
