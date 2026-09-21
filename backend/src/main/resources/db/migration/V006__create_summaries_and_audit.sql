-- ダッシュボード用の読み取りモデル。判定完了時に更新する。
CREATE TABLE repository_summaries (
    repository_id         uuid        PRIMARY KEY REFERENCES repositories(id) ON DELETE CASCADE,
    latest_run_id         uuid        REFERENCES runs(id) ON DELETE SET NULL,
    latest_verdict        varchar(24),
    latest_completeness   varchar(8),
    latest_measured_at    timestamptz,
    last_full_run_id      uuid        REFERENCES runs(id) ON DELETE SET NULL,
    last_full_measured_at timestamptz,
    category_status       jsonb,
    open_critical_count   int         NOT NULL DEFAULT 0,
    open_high_count       int         NOT NULL DEFAULT 0,
    active_waiver_count   int         NOT NULL DEFAULT 0,
    version               bigint      NOT NULL DEFAULT 0,
    updated_at            timestamptz NOT NULL DEFAULT now()
);

-- 追記専用。アプリ用ロールからの UPDATE / DELETE は V006 末尾で剥奪する。
CREATE TABLE audit_logs (
    id            uuid        PRIMARY KEY,
    actor_user_id uuid        REFERENCES users(id) ON DELETE SET NULL,
    actor_login   varchar(39),
    action        varchar(64) NOT NULL,
    target_type   varchar(32) NOT NULL,
    target_id     varchar(64),
    before_value  jsonb,
    after_value   jsonb,
    client_ip     inet,
    occurred_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_logs_occurred ON audit_logs (occurred_at DESC);
CREATE INDEX ix_audit_logs_target   ON audit_logs (target_type, target_id);

-- 監査ログの改変をアプリ側の実装ミスで起こさないよう、権限で塞ぐ。
-- 実運用ではアプリ専用ロール（例: quality_gate_app）に対して実行する。
-- 開発環境では所有者ロールで接続するため、存在する場合のみ適用する。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'quality_gate_app') THEN
        EXECUTE 'REVOKE UPDATE, DELETE ON audit_logs FROM quality_gate_app';
    END IF;
END
$$;
