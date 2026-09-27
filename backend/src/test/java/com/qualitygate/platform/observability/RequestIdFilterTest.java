package com.qualitygate.platform.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void 採番したIDを応答とMDCに載せる() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        // 呼び出し側の値は使わない
        request.addHeader("X-Request-Id", "evil\nlog-injection");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, capture(seen));

        assertThat(seen.get()).matches("[0-9a-f-]{36}");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen.get());
        // 要求が終われば MDC から消す（スレッドは使い回される）
        assertThat(MDC.get(CorrelationIds.REQUEST_ID)).isNull();
    }

    private static FilterChain capture(AtomicReference<String> seen) {
        return (req, res) -> seen.set(MDC.get(CorrelationIds.REQUEST_ID));
    }
}
