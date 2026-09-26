package com.qualitygate.platform.error;

import org.springframework.http.HttpStatus;

/**
 * API が返す機械可読なエラー識別子（docs/initial/07-api-design.md 7 章）。
 *
 * <p>クライアントはこの値で分岐する。{@code title} と {@code detail} は
 * 人間向けであり、文言の改善で変わりうるため依存してはならない。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "リクエストの内容が不正です"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "認証が必要です"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Ingest Token が不正または失効しています"),
    USER_NOT_ALLOWLISTED(HttpStatus.FORBIDDEN, "許可リストに登録されていません"),
    USER_DISABLED(HttpStatus.FORBIDDEN, "アカウントが無効です"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "この操作を行う権限がありません"),
    REPOSITORY_MISMATCH(HttpStatus.FORBIDDEN, "トークンの発行元リポジトリと一致しません"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "対象が見つかりません"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "この URL ではこの HTTP メソッドを使えません"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "要求された形式では応答できません"),
    RUN_ALREADY_FINALIZED(HttpStatus.CONFLICT, "この Run は既に確定しています"),
    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "同じ GitHub ログイン名の利用者が既に登録されています"),
    ADMIN_REQUIRED(HttpStatus.CONFLICT, "管理者が 1 人以上必要です"),
    REPOSITORY_ALREADY_EXISTS(HttpStatus.CONFLICT, "同じリポジトリが既に登録されています"),
    WAIVER_ALREADY_EXISTS(HttpStatus.CONFLICT, "同じ対象に有効な免除が既に存在します"),
    RUN_NOT_EVALUABLE(HttpStatus.CONFLICT, "この Run はまだ判定できる状態ではありません"),
    ARTIFACTS_DELETED(HttpStatus.CONFLICT, "成果物が保持期間を過ぎて削除されています"),
    ARTIFACT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "成果物のサイズが上限を超えています"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "本文の形式（Content-Type）に対応していません"),
    ARTIFACT_TYPE_UNKNOWN(HttpStatus.UNPROCESSABLE_ENTITY, "未知の成果物種別です"),
    ARTIFACT_FORMAT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "成果物の形式が不正です"),
    PERFORMANCE_METADATA_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "性能計測のメタデータが不足しています"),
    MUTATION_SCOPE_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "ミューテーションテストの実行範囲が指定されていません"),
    CONFIG_VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "設定ファイルの内容が不正です"),
    WAIVER_EXPIRY_TOO_FAR(HttpStatus.UNPROCESSABLE_ENTITY, "免除の期限が上限を超えています"),
    GITHUB_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "GitHub API に接続できません"),
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
