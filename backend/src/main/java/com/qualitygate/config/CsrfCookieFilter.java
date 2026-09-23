package com.qualitygate.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CSRF トークンを毎回読み出し、XSRF-TOKEN Cookie を確実に発行させる。
 *
 * <p>Spring Security はトークンを遅延生成し、誰も読まなければ Cookie を書かない。
 * SPA は画面を表示しただけではトークンに触れないため、最初の更新操作が
 * Cookie の無いまま送られて 403 になる。
 */
class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
