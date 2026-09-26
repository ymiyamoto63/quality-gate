package com.qualitygate.platform.observability;

import org.slf4j.MDC;

import java.util.Optional;

/**
 * ログの相関 ID（docs/spec/05-architecture.md 10.1）。MDC のキーを 1 か所に置く。
 *
 * <p>{@code requestId} は HTTP の要求ごと（{@link RequestIdFilter}）、{@code runId} は Run を扱う要求と
 * ジョブ（取り込み・判定）で MDC に載せる。JSON 形式のログ（{@code QG_LOG_FORMAT}）では、MDC の値が
 * そのまま項目になる。
 */
public final class CorrelationIds {

    public static final String REQUEST_ID = "requestId";
    public static final String RUN_ID = "runId";

    /** 要求・応答のヘッダ。呼び出し側が付ければそれを使い、無ければ採番して応答に返す。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private CorrelationIds() {
    }

    /** 現在の要求の ID（エラー応答の {@code traceId} に使う）。 */
    public static Optional<String> currentRequestId() {
        return Optional.ofNullable(MDC.get(REQUEST_ID));
    }
}
