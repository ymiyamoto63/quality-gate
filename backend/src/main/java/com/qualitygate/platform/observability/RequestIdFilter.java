package com.qualitygate.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 要求ごとの相関 ID（{@code requestId}）を採番して MDC に載せ、{@code X-Request-Id} ヘッダで応答に返す。
 * エラー応答の {@code traceId} も同じ値になる（GlobalExceptionHandler）。
 */
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(CorrelationIds.REQUEST_ID, requestId);
        response.setHeader(CorrelationIds.REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIds.REQUEST_ID);
            MDC.remove(CorrelationIds.RUN_ID);
        }
    }
}
