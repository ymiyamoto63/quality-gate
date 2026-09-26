package com.qualitygate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全マイグレーションが空の DB に適用できることを検証する。
 *
 * <p>マイグレーションの誤りは起動時まで気づけないことが多い。
 * 実際の PostgreSQL に対して毎回流し、CHECK 制約や部分一意インデックスといった
 * 本番で効いている仕組みごと検証する。
 */
@SpringBootTest
@AbstractIntegrationTest
class FlywayMigrationIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 全テーブルが作成される() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "users", "repositories", "components", "ingest_tokens",
                "gate_configs", "runs", "run_skipped_metrics", "artifacts",
                "measurements", "findings", "jobs",
                "repository_summaries", "audit_logs",
                "flyway_schema_history");
        // 免除と通知は廃止した（D-22）
        assertThat(tables).doesNotContain("waivers", "notifications", "notification_settings");
    }

    @Test
    void トレンド検索用の複合インデックスがある() {
        List<String> indexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where tablename = 'measurements'", String.class);

        // 3 年で数百万行になる。期間指定だけでは足りない。
        assertThat(indexes).contains("ix_measurements_trend");
    }
}
