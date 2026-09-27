package com.qualitygate.platform.error;

import org.springframework.http.HttpStatus;

/**
 * API が返す機械可読なエラー識別子（docs/architecture.md 6.3）。
 *
 * <p>クライアントはこの値で分岐する。{@code title} と {@code detail} は
 * 人間向けであり、文言の改善で変わりうるため依存してはならない。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "リクエストの内容が不正です"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "認証が必要です"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Ingest Token が不正または失効しています"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "この操作を行う権限がありません"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "対象が見つかりません"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "この URL ではこの HTTP メソッドを使えません"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "要求された形式では応答できません"),
    RUN_ALREADY_FINALIZED(HttpStatus.CONFLICT, "この Run は既に確定しています"),
    ARTIFACT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "成果物のサイズが上限を超えています"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "本文の形式（Content-Type）に対応していません"),
    ARTIFACT_TYPE_UNKNOWN(HttpStatus.UNPROCESSABLE_CONTENT, "未知の成果物種別です"),
    ARTIFACT_FORMAT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "成果物の形式が不正です"),
    PERFORMANCE_METADATA_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "性能計測のメタデータが不足しています"),
    MUTATION_SCOPE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "ミューテーションテストの実行範囲が指定されていません"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "サーバ内部でエラーが発生しました");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
