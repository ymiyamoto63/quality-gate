package com.qualitygate.auth;

import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.repo.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * セッションのロールを、リクエストのたびに {@code users} の現在値で置き換える。
 *
 * <p>OAuth のログイン時に付けたロールをセッションに持ち続けると、管理者が
 * ロールを下げたり利用者を無効化したりしても、その利用者がログアウトするまで
 * （最長 8 時間）元の権限で操作できてしまう。許可リストが入口の唯一の制御である以上、
 * 変更は次のリクエストから効かなければならない。
 *
 * <p>無効化された・許可リストから外れた利用者のセッションは破棄し、未認証として扱う。
 */
public class SessionUserRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionUserRefreshFilter.class);

    private final UserAccountRepository users;

    public SessionUserRefreshFilter(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof OAuth2AuthenticationToken token) {
            Optional<UserAccount> user = users.findByGithubLoginIgnoreCase(token.getName())
                    .filter(UserAccount::isActive);
            if (user.isEmpty()) {
                log.warn("無効化または許可リストから外れた利用者のセッションを破棄します login={}",
                        token.getName());
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            } else {
                // セッションに保存された文脈は書き換えず、このリクエストだけに現在のロールを載せる
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new OAuth2AuthenticationToken(token.getPrincipal(),
                        List.of(new SimpleGrantedAuthority(user.get().getRole().authority())),
                        token.getAuthorizedClientRegistrationId()));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
