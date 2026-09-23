package com.qualitygate.config;

import com.qualitygate.auth.AllowlistOAuth2UserService;
import com.qualitygate.auth.SessionUserRefreshFilter;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.domain.repo.IngestTokenRepository;
import com.qualitygate.ingest.security.IngestTokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 認証の 2 経路を分ける。
 *
 * <ul>
 *   <li>Ingest API: Ingest Token。ステートレス、CSRF 対象外（Cookie を用いないため）</li>
 *   <li>それ以外: GitHub OAuth ログイン後のセッション Cookie。CSRF 有効</li>
 * </ul>
 *
 * <p>SPA を同一オリジンで配信するため CORS 設定は行わない。
 *
 * <p>本クラスは複数モジュール（auth / ingest）を組み立てる合成点であるため、
 * 共通基盤である platform ではなく config に置く。platform が業務モジュールを
 * 知らない状態を保つためであり、この規則は ModuleDependencyTest が検証する。
 * Cookie 認証で CSRF 対策を省くと、外部サイトから利用者の権限で操作が実行できてしまう。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Ingest API の対象判定。
     *
     * <p>パスパターン API のバージョン差に依存しないよう、明示的に書く。
     */
    static boolean isIngestRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/v1/runs")) {
            return false;
        }
        return "POST".equals(request.getMethod()) || uri.endsWith("/status");
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain ingestFilterChain(HttpSecurity http, IngestTokenRepository tokens)
            throws Exception {
        RequestMatcher ingestMatcher = SecurityConfig::isIngestRequest;
        return http
                .securityMatcher(ingestMatcher)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INGEST"))
                .addFilterBefore(new IngestTokenAuthenticationFilter(tokens),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    SecurityFilterChain appFilterChain(HttpSecurity http,
                                       AllowlistOAuth2UserService userService,
                                       UserAccountRepository users) throws Exception {
        return http
                // Cookie 認証で CSRF 対策を省くと、外部サイトから利用者の権限で免除登録や
                // 設定変更が実行できてしまう。SPA は XSRF-TOKEN Cookie の値を
                // X-XSRF-TOKEN ヘッダで送り返す（frontend/src/api/client.ts）
                .csrf(csrf -> csrf.spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                // ロールの変更・無効化を次のリクエストから反映する（ログアウトを待たない）
                .addFilterBefore(new SessionUserRefreshFilter(users), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // データを返すのは API だけ。保護すべきはここ。
                        .requestMatchers("/api/**").authenticated()
                        // 監視系は health のみ公開し、メトリクスは内部向けに閉じる
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // SPA のシェルと静的リソースは公開する。
                        // 未ログインでも index.html を返し、/api/v1/me の 401 を受けて
                        // フロント側が /login へ誘導する。ここを保護すると、
                        // URL を直接開いたときに画面ではなく 401 が返ってしまう。
                        .anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(u -> u.userService(userService))
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/forbidden"))
                .logout(Customizer.withDefaults())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
