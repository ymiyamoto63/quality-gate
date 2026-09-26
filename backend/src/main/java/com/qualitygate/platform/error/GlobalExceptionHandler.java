package com.qualitygate.platform.error;

import com.qualitygate.platform.observability.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
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

    /**
     * メソッドセキュリティ（{@code @PreAuthorize}）の拒否。既定の 500 にせず 403 で返す。
     * 画面は無効化したボタンで権限の無さを示すが、防御はこの応答が担う。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        log.warn("権限不足: {}", ex.getMessage());
        return problemOf(ErrorCode.FORBIDDEN, "この操作には管理者権限が必要です");
    }

    /** 本文が JSON として読めない・型が合わない。サーバの異常ではない。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return problemOf(ErrorCode.VALIDATION_FAILED,
                "リクエストの本文を解釈できません。JSON の形式と値の型を確認してください");
    }

    /** パスやクエリの値の型が合わない（UUID の形でない ID など）。サーバの異常ではない。 */
    @ExceptionHandler(TypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(TypeMismatchException ex) {
        return problemOf(ErrorCode.VALIDATION_FAILED,
                "%s の値の形式が不正です（受信値: %s）".formatted(ex.getPropertyName(), ex.getValue()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        // Spring MVC が要求の誤りとして投げる例外（405 / 415 / 404 / 必須パラメータの欠落など）は
        // 状態コードを持っている。まとめて 500 にすると、利用者の誤りがサーバの異常に見える
        if (ex instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            return handleClientError(ex, response);
        }
        log.error("想定外のエラー", ex);
        ProblemDetail problem = problemOf(ErrorCode.INTERNAL_ERROR, "サーバ内部でエラーが発生しました");
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private ResponseEntity<ProblemDetail> handleClientError(Exception ex, ErrorResponse response) {
        log.warn("要求の誤り: status={} {}", response.getStatusCode().value(), ex.getMessage());
        ErrorCode code = switch (response.getStatusCode().value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 413 -> ErrorCode.ARTIFACT_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.VALIDATION_FAILED;
        };
        ProblemDetail problem = problemOf(code, code.title() + "（" + response.getBody().getDetail() + "）");
        // 405 の Allow や 415 の Accept など、Spring が組み立てたヘッダをそのまま返す
        return ResponseEntity.status(problem.getStatus()).headers(response.getHeaders()).body(problem);
    }

    private ProblemDetail problemOf(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(PROBLEM_BASE + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.title());
        problem.setProperty("errorCode", code.name());
        problem.setProperty("timestamp", Instant.now().toString());
        // サーバログの相関 ID と同じ値。利用者が問い合わせるときに、ログを辿る手がかりになる
        CorrelationIds.currentRequestId().ifPresent(id -> problem.setProperty("traceId", id));
        return problem;
    }
}
