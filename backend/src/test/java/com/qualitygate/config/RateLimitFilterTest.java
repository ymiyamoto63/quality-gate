package com.qualitygate.config;

import com.qualitygate.ingest.security.IngestAuthentication;
import com.qualitygate.platform.ratelimit.RateLimitProperties;
import com.qualitygate.platform.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(new RateLimiter(),
            new RateLimitProperties(true, 2, 3, 4, 5), JsonMapper.builder().build(), null);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void IngestTokenの要求はアップロードとそれ以外で枠を分ける() {
        UUID token = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new IngestAuthentication(UUID.randomUUID(), token));

        assertThat(filter.limitOf(request("POST", "/api/v1/runs/abc/artifacts")))
                .isEqualTo(new RateLimitFilter.Limit("upload", "token:" + token, 3));
        assertThat(filter.limitOf(request("POST", "/api/v1/runs")))
                .isEqualTo(new RateLimitFilter.Limit("ingest", "token:" + token, 2));
        assertThat(filter.limitOf(request("POST", "/api/v1/runs/abc/finalize")))
                .isEqualTo(new RateLimitFilter.Limit("ingest", "token:" + token, 2));
    }

    @Test
    void 参照APIはログインした利用者ごと未ログインはIPごと() {
        assertThat(filter.limitOf(request("GET", "/api/v1/dashboard")))
                .isEqualTo(new RateLimitFilter.Limit("query", "ip:10.0.0.1", 4));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "octocat", null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
        assertThat(filter.limitOf(request("GET", "/api/v1/dashboard")))
                .isEqualTo(new RateLimitFilter.Limit("query", "user:octocat", 4));
    }

    @Test
    void 画面と監視は制限しない() {
        assertThat(filter.limitOf(request("GET", "/runs/abc"))).isNull();
        assertThat(filter.limitOf(request("GET", "/actuator/health"))).isNull();
    }

    @Test
    void 上限を超えるとRFC9457の形で429を返す() throws Exception {
        MockHttpServletResponse last = null;
        for (int i = 0; i < 5; i++) {
            last = new MockHttpServletResponse();
            filter.doFilter(request("GET", "/api/v1/runs"), last, new MockFilterChain());
        }

        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getHeader("Retry-After")).isEqualTo("15");
        assertThat(last.getContentType()).startsWith("application/problem+json");
        assertThat(last.getContentAsString()).contains("\"errorCode\":\"RATE_LIMITED\"").contains("参照 API");
    }

    @Test
    void 無効にすれば制限しない() throws Exception {
        RateLimitFilter disabled = new RateLimitFilter(new RateLimiter(),
                new RateLimitProperties(false, 1, 1, 1, 1), JsonMapper.builder().build(), null);
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            disabled.doFilter(request("GET", "/api/v1/runs"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("10.0.0.1");
        return request;
    }
}
