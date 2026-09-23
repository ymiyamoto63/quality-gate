package com.qualitygate;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * GitHub OAuth でログインした状態を作る。
 *
 * <p>本番と同じ {@code OAuth2AuthenticationToken} を使うため、ロールの再読み込み
 * （SessionUserRefreshFilter）も本番どおりに働く。{@code sessionRole} は
 * ログイン時にセッションへ載ったロールで、DB の現在値とずれている状況を作れる。
 */
final class TestSessions {

    private TestSessions() {
    }

    static MockMvcTester tester(WebApplicationContext context) {
        return MockMvcTester.create(MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity()).build());
    }

    /** ログイン中の利用者として、CSRF トークン付きで送る。 */
    static RequestPostProcessor as(String login, String sessionRole) {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_" + sessionRole)),
                Map.of("login", login, "id", 1), "login");
        RequestPostProcessor session = oauth2Login().oauth2User(user);
        return request -> csrf().postProcessRequest(session.postProcessRequest(request));
    }

    /** CSRF トークンを付けずに送る（CSRF 対策が効いていることの確認用）。 */
    static RequestPostProcessor withoutCsrf(String login, String sessionRole) {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_" + sessionRole)),
                Map.of("login", login, "id", 1), "login");
        return oauth2Login().oauth2User(user);
    }
}
