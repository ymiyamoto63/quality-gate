package com.qualitygate.platform.security;

import java.util.UUID;

/**
 * 操作の実行者。監査ログの「誰が」に使う。
 *
 * @param userId   利用者 ID。システム（日次バッチ）の操作では null
 * @param login    GitHub ログイン名。システムの操作では {@code "system"}
 * @param clientIp 操作元の IP。バッチでは null
 */
public record Actor(UUID userId, String login, String clientIp) {

    /** 日次バッチなど、人の操作ではないもの。 */
    public static final Actor SYSTEM = new Actor(null, "system", null);
}
