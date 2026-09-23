package com.qualitygate.config;

import com.qualitygate.ingest.security.IngestAuthentication;
import com.qualitygate.platform.observability.CorrelationIds;
import com.qualitygate.platform.ratelimit.RateLimitProperties;
import com.qualitygate.platform.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * API のレート制限（docs/initial/07-api-design.md 8 章）。超過したら 429 と {@code Retry-After} を返す。
 *
 * <p>認証の後に置き、制限の単位を認証の結果で決める。
 * <ul>
 *   <li>Ingest API: Ingest Token ごと。成果物のアップロードは別枠（1 Run に多数のファイルを送るため）</li>
 *   <li>参照 API: ログインした利用者ごと。未ログインは IP ごと（どのみち 401 になる）</li>
 *   <li>バッジ: IP ごと</li>
 * </ul>
 * API 以外（画面の静的ファイル、監視）は制限しない。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Pattern ARTIFACT_UPLOAD = Pattern.compile("^/api/v1/runs/[^/]+/artifacts$");

    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, ObjectMapper objectMapper,
                           MeterRegistry meterRegistry) {
        this.limiter = limiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Limit limit = properties.enabled() ? limitOf(request) : null;
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }
        RateLimiter.Decision decision = limiter.tryAcquire(limit.category() + ":" + limit.subject(), limit.perMinute());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("レート制限を超えました category={} subject={} path={}", limit.category(), limit.subject(),
                request.getRequestURI());
        if (meterRegistry != null) {
            meterRegistry.counter("qg.rate_limit.rejected", "category", limit.category()).increment();
        }
        reject(response, limit, decision.retryAfterSeconds());
    }

    Limit limitOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/badges/")) {
            return new Limit("badge", "ip:" + request.getRemoteAddr(), properties.badgePerMinute());
        }
        if (!uri.startsWith("/api/")) {
            return null;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof IngestAuthentication ingest) {
            boolean upload = "POST".equals(request.getMethod()) && ARTIFACT_UPLOAD.matcher(uri).matches();
            return upload
                    ? new Limit("upload", "token:" + ingest.tokenId(), properties.uploadPerMinute())
                    : new Limit("ingest", "token:" + ingest.tokenId(), properties.ingestPerMinute());
        }
        String subject = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                ? "user:" + authentication.getName()
                : "ip:" + request.getRemoteAddr();
        return new Limit("query", subject, properties.queryPerMinute());
    }

    /** エラー応答は他の API と同じ RFC 9457 の形にする（GlobalExceptionHandler と同じ項目）。 */
    private void reject(HttpServletResponse response, Limit limit, long retryAfterSeconds) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://quality-gate.example/problems/rate-limited");
        problem.put("title", "リクエストが多すぎます");
        problem.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        problem.put("detail", "リクエストが多すぎます（%s は 1 分あたり %d 回まで）。%d 秒後に再試行してください"
                .formatted(labelOf(limit.category()), limit.perMinute(), retryAfterSeconds));
        problem.put("errorCode", "RATE_LIMITED");
        problem.put("timestamp", Instant.now().toString());
        CorrelationIds.currentRequestId().ifPresent(id -> problem.put("traceId", id));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private static String labelOf(String category) {
        return switch (category) {
            case "upload" -> "成果物のアップロード";
            case "ingest" -> "取り込み API";
            case "badge" -> "バッジ";
            default -> "参照 API";
        };
    }

    record Limit(String category, String subject, int perMinute) {
    }
}
