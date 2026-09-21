package com.qualitygate.platform.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** すべてのエラー応答を RFC 9457 の形式に揃える。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://quality-gate.example/problems/";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        // 入力の誤りはシステム異常ではない。日常的に起こる正常系として WARN に留める。
        log.warn("API エラー: code={} detail={}", ex.errorCode(), ex.getMessage());
        ProblemDetail problem = problemOf(ex.errorCode(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        ProblemDetail problem = problemOf(ErrorCode.VALIDATION_FAILED, "入力値を確認してください");
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("想定外のエラー", ex);
        return problemOf(ErrorCode.INTERNAL_ERROR, "サーバ内部でエラーが発生しました");
    }

    private ProblemDetail problemOf(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(PROBLEM_BASE + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.title());
        problem.setProperty("errorCode", code.name());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
