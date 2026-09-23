package com.qualitygate.platform.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * レート制限の上限（{@code quality-gate.rate-limit.*}。docs/initial/07-api-design.md 8 章）。
 *
 * <p>内部利用のため厳しくはせず、CI の不具合で同じジョブが無限に再実行されるような事故で
 * ストレージと DB が食い潰されるのを防ぐ歯止めとする。
 *
 * @param enabled         制限するか
 * @param ingestPerMinute Ingest API（成果物のアップロードを除く）のトークンあたりの上限
 * @param uploadPerMinute 成果物のアップロードのトークンあたりの上限
 * @param queryPerMinute  参照 API の利用者（未ログインなら IP）あたりの上限
 * @param badgePerMinute  バッジの IP あたりの上限
 */
@ConfigurationProperties(prefix = "quality-gate.rate-limit")
public record RateLimitProperties(
        Boolean enabled,
        int ingestPerMinute,
        int uploadPerMinute,
        int queryPerMinute,
        int badgePerMinute) {

    public RateLimitProperties {
        if (enabled == null) {
            enabled = true;
        }
        ingestPerMinute = ingestPerMinute > 0 ? ingestPerMinute : 60;
        uploadPerMinute = uploadPerMinute > 0 ? uploadPerMinute : 100;
        queryPerMinute = queryPerMinute > 0 ? queryPerMinute : 600;
        badgePerMinute = badgePerMinute > 0 ? badgePerMinute : 60;
    }
}
