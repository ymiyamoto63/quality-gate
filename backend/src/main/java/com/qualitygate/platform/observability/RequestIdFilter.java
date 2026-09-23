package com.qualitygate.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 要求ごとの相関 ID（{@code requestId}）を MDC に載せ、{@code X-Request-Id} ヘッダで応答に返す。
 *
 * <p>呼び出し側（CI やリバースプロキシ）が {@code X-Request-Id} を付けていればそれを使い、両側のログを
 * 同じ ID で辿れるようにする。ログに載せるため、形の怪しい値（改行や長すぎる値）は使わず採番し直す。
 * エラー応答の {@code traceId} も同じ値になる（GlobalExceptionHandler）。
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern ACCEPTABLE = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(CorrelationIds.REQUEST_ID_HEADER);
        String requestId = given != null && ACCEPTABLE.matcher(given).matches()
                ? given
                : UUID.randomUUID().toString();
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
