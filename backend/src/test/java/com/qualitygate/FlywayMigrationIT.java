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
    void 必要なテーブルだけがある() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "repositories", "runs", "artifacts", "measurements", "findings", "flyway_schema_history");
    }

    @Test
    void 判定は合格と不合格の2値しか持たない() {
        // 注意（WARN）や部分計測（SKIP）は V002 でやめた。制約で入らないようにする
        List<String> checks = jdbcTemplate.queryForList(
                "select pg_get_constraintdef(oid) from pg_constraint where conname in "
                        + "('runs_verdict_check', 'measurements_status_check')", String.class);

        assertThat(checks).hasSize(2).noneMatch(check -> check.contains("WARN") || check.contains("SKIP"));
    }
}
