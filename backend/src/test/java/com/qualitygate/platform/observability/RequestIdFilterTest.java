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
    void 呼び出し側のIDを使い応答とMDCに載せる() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.addHeader("X-Request-Id", "ci-run-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, capture(seen));

        assertThat(seen.get()).isEqualTo("ci-run-42");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("ci-run-42");
        // 要求が終われば MDC から消す（スレッドは使い回される）
        assertThat(MDC.get(CorrelationIds.REQUEST_ID)).isNull();
    }

    @Test
    void IDが無いか形が怪しければ採番する() throws Exception {
        for (String given : new String[] {null, "evil\nlog-injection", "x".repeat(65)}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
            if (given != null) {
                request.addHeader("X-Request-Id", given);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<String> seen = new AtomicReference<>();

            filter.doFilter(request, response, capture(seen));

            assertThat(seen.get()).matches("[0-9a-f-]{36}");
            assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen.get());
        }
    }

    private static FilterChain capture(AtomicReference<String> seen) {
        return (req, res) -> seen.set(MDC.get(CorrelationIds.REQUEST_ID));
    }
}
