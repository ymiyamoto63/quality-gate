package com.qualitygate.platform.observability;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * パスに {@code {runId}} を含む API（取り込み・Run 詳細など）の間、{@code runId} を MDC に載せる。
 *
 * <p>コントローラごとに書かずに済むよう、パスの変数から拾う。
 */
public class RunIdMdcInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                && variables.get(CorrelationIds.RUN_ID) instanceof String runId) {
            MDC.put(CorrelationIds.RUN_ID, runId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        MDC.remove(CorrelationIds.RUN_ID);
    }
}
