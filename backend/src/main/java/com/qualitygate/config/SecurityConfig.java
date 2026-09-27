package com.qualitygate.config;

import com.qualitygate.ingest.security.IngestTokenAuthenticationFilter;
import com.qualitygate.platform.config.QualityGateProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 認証の 2 経路を分ける。
 *
 * <ul>
 *   <li>Ingest API: Ingest Token。ステートレス、CSRF 対象外（Cookie を用いないため）</li>
 *   <li>画面: 共有のユーザー名とパスワード（{@code QG_LOGIN_USERNAME} / {@code QG_LOGIN_PASSWORD}）で
 *       ログインした後のセッション Cookie。CSRF 有効</li>
 * </ul>
 *
 * <p>ロールは無い。ログインした人は全員、同じリリース判定を見る。見るだけで、画面から変えられるものは無い。
 * 経営陣が GitHub のアカウントを持っていなくても見られるよう、GitHub のログインは使わない。
 *
 * <p>SPA を同一オリジンで配信するため CORS 設定は行わない。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Ingest API の対象判定。{@code /api/v1/runs} 配下の POST だけ。 */
    static boolean isIngestRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/runs") && "POST".equals(request.getMethod());
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain ingestFilterChain(HttpSecurity http, QualityGateProperties properties) throws Exception {
        RequestMatcher ingestMatcher = SecurityConfig::isIngestRequest;
        return http
                .securityMatcher(ingestMatcher)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INGEST"))
                .addFilterBefore(new IngestTokenAuthenticationFilter(properties.ingestTokens()),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    SecurityFilterChain appFilterChain(HttpSecurity http) throws Exception {
        return http
                // SPA は XSRF-TOKEN Cookie の値を X-XSRF-TOKEN ヘッダで送り返す（frontend/src/api/client.ts）。
                // ログインも対象にする（外部サイトから別のセッションへログインさせられるのを防ぐ）
                .csrf(csrf -> csrf.spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // データを返すのは API だけ。保護すべきはここ。
                        .requestMatchers("/api/**").authenticated()
                        // 監視系は health のみ公開し、メトリクスは内部向けに閉じる
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        // SPA のシェルと静的リソースは公開する。
                        // 未ログインでも index.html を返し、/api/v1/me の 401 を受けて
                        // フロント側がログインの画面を出す。ここを保護すると、
                        // URL を直接開いたときに画面ではなく 401 が返ってしまう。
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/v1/login")
                        .successHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value()))
                        .failureHandler((request, response, exception) ->
                                response.sendError(HttpStatus.UNAUTHORIZED.value())))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 共有のアカウント 1 つだけ。パスワードは起動時にハッシュにし、平文をメモリ上の利用者情報に残さない。 */
    @Bean
    UserDetailsService userDetailsService(QualityGateProperties properties, PasswordEncoder encoder) {
        QualityGateProperties.Login login = properties.login();
        return new InMemoryUserDetailsManager(User.withUsername(login.username())
                .password(encoder.encode(login.password()))
                .roles("VIEWER")
                .build());
    }
}
