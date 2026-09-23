package com.qualitygate;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 結合テストの前に全テーブルを空にする。
 *
 * <p>結合テストは Spring のコンテキスト（と PostgreSQL のコンテナ）を共有する。
 * テストごとに消すテーブルを列挙すると、テーブルを足したときに消し漏れが生じ、
 * 別のテストの外部キーに引っかかって順序依存の失敗になる。
 */
final class IntegrationCleanup {

    private IntegrationCleanup() {
    }

    static void deleteAll(JdbcTemplate jdbc) {
        jdbc.execute("""
                TRUNCATE audit_logs, notifications, notification_settings, system_settings, jobs, findings,
                         waivers, measurements, artifacts, run_skipped_metrics,
                         repository_summaries, runs, gate_configs, ingest_tokens, components,
                         repositories, users CASCADE
                """);
    }
}
