package com.qualitygate.platform.security;

import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ログイン中の利用者を引く。
 *
 * <p>認証情報の名前（GitHub のログイン名）から {@code users} の行を引き直す。
 * セッションに保存された情報をそのまま信じないのは、ロールの変更や無効化を
 * 次のリクエストから反映させるためである。
 */
@Component
public class CurrentUser {

    private final UserAccountRepository users;

    public CurrentUser(UserAccountRepository users) {
        this.users = users;
    }

    public UserAccount require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "ログインしてください");
        }
        return users.findByGithubLoginIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_ALLOWLISTED,
                        "許可リストに登録されていません"));
    }

    /** 監査ログに残す実行者。 */
    public Actor actor() {
        UserAccount user = require();
        return new Actor(user.getId(), user.getGithubLogin(), clientIp());
    }

    /**
     * 操作元の IP。リバースプロキシの内側では X-Forwarded-For を Spring の
     * {@code server.forward-headers-strategy} で解決させる前提とし、ここでは
     * ヘッダを直接読まない（偽装されたヘッダを記録しないため）。
     */
    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
