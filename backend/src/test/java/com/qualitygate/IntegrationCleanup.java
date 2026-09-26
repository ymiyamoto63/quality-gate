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

    /** 結合テストの Ingest Token（application-test.yml の {@code quality-gate.ingest-tokens}）。 */
    static final String INGEST_TOKEN = "test-ingest-token-0123456789abcdef";

    private IntegrationCleanup() {
    }

    static void deleteAll(JdbcTemplate jdbc) {
        jdbc.execute("""
                TRUNCATE audit_logs, system_settings, findings,
                         measurements, artifacts, run_skipped_metrics,
                         runs, gate_configs,
                         repositories, users CASCADE
                """);
    }
}
