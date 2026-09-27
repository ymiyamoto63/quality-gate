-- quality-gate のスキーマ（docs/spec/06-database-design.md）。
-- 変更はこのファイルを書き換えず、V002 以降のマイグレーションを追加する。

-- 利用者と許可リスト。このテーブルに行が無い GitHub ユーザーはログインできない。
CREATE TABLE users (
    id              uuid         PRIMARY KEY,
    github_login    varchar(39)  NOT NULL UNIQUE,
    github_user_id  bigint       UNIQUE,
    display_name    varchar(255),
    avatar_url      varchar(512),
    role            varchar(16)  NOT NULL DEFAULT 'VIEWER',
    status          varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    created_by      uuid         REFERENCES users(id) ON DELETE SET NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    last_login_at   timestamptz,
    CONSTRAINT users_role_check   CHECK (role   IN ('ADMIN','VIEWER')),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE','DISABLED'))
);

-- 計測対象リポジトリ。収集ランナーが初めて計測を送ったときに作られる。
CREATE TABLE repositories (
    id              uuid         PRIMARY KEY,
    owner           varchar(39)  NOT NULL,
    name            varchar(100) NOT NULL,
    default_branch  varchar(255) NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT repositories_full_name_key UNIQUE (owner, name)
);

-- 1 コミットに対する 1 回の計測・判定。同じコミットの再送信は attempt を増やす。
CREATE TABLE runs (
    id                   uuid         PRIMARY KEY,
    repository_id        uuid         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha           char(40)     NOT NULL,
    base_commit_sha      char(40),
    branch               varchar(255) NOT NULL,
    pull_request_number  integer,
    attempt              integer      NOT NULL DEFAULT 1,
    triggered_by         varchar(64)  NOT NULL,
    ci_run_url           varchar(512),
    measured_at          timestamptz  NOT NULL,
    tags                 text[]       NOT NULL DEFAULT '{}',
    config_commit_sha    char(40),
    baseline_run_id      uuid         REFERENCES runs(id) ON DELETE SET NULL,
    status               varchar(16)  NOT NULL,
    verdict              varchar(24),
    completeness         varchar(8),
    error_code           varchar(64),
    error_detail         text,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    evaluated_at         timestamptz,
    CONSTRAINT runs_attempt_key UNIQUE (repository_id, commit_sha, attempt),
    CONSTRAINT runs_status_check CHECK (status IN
        ('CREATED','UPLOADING','FINALIZED','PROCESSING','EVALUATED','FAILED','ABANDONED')),
    CONSTRAINT runs_verdict_check CHECK (verdict IS NULL OR verdict IN ('PASS','PASS_WITH_WARNINGS','FAIL')),
    CONSTRAINT runs_completeness_check CHECK (completeness IS NULL OR completeness IN ('FULL','PARTIAL'))
);
CREATE INDEX ix_runs_latest    ON runs (repository_id, measured_at DESC, attempt DESC) WHERE status = 'EVALUATED';
CREATE INDEX ix_runs_list      ON runs (repository_id, branch, measured_at DESC);
CREATE INDEX ix_runs_retention ON runs (measured_at);
CREATE INDEX ix_runs_tags      ON runs USING gin (tags);

-- 収集ランナーが申告した、計測しなかった指標。受理するかは判定時に合格ラインで決める。
CREATE TABLE run_skipped_metrics (
    run_id     uuid         NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    metric_id  varchar(8)   NOT NULL,
    reason     varchar(512) NOT NULL,
    accepted   boolean,
    PRIMARY KEY (run_id, metric_id)
);
COMMENT ON COLUMN run_skipped_metrics.accepted IS
    'NULL=未判定 / true=設定が許容し SKIP / false=設定が許容せず ERROR';

-- 取り込んだ成果物のメタデータ。実体は ArtifactStore（ローカルファイル）に置く。
CREATE TABLE artifacts (
    id              uuid         PRIMARY KEY,
    run_id          uuid         NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    type            varchar(32)  NOT NULL,
    filename        varchar(255) NOT NULL,
    size_bytes      bigint       NOT NULL,
    sha256          char(64)     NOT NULL,
    storage_key     varchar(512) NOT NULL,
    component_name  varchar(64),
    metadata        jsonb,
    uploaded_at     timestamptz  NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    CONSTRAINT artifacts_unique_key UNIQUE (run_id, type, filename)
);

-- 指標ごとの判定結果。repository_id と measured_at はトレンドの検索のために Run から複製する。
CREATE TABLE measurements (
    id              uuid          PRIMARY KEY,
    run_id          uuid          NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    repository_id   uuid          NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    metric_id       varchar(8)    NOT NULL,
    component_name  varchar(64),
    scenario        varchar(64),
    variant         varchar(64),
    status          varchar(16)   NOT NULL,
    value           numeric(12,4),
    unit            varchar(16),
    threshold       jsonb,
    previous_value  numeric(12,4),
    reason          varchar(512),
    detail          jsonb,
    measured_at     timestamptz   NOT NULL,
    CONSTRAINT measurements_status_check CHECK (status IN
        ('PASS','WARN','FAIL','SKIP','ERROR','NOT_APPLICABLE'))
);
CREATE UNIQUE INDEX ux_measurements_key ON measurements
    (run_id, metric_id, COALESCE(component_name, ''), COALESCE(scenario, ''), COALESCE(variant, ''));
CREATE INDEX ix_measurements_run   ON measurements (run_id);
CREATE INDEX ix_measurements_trend ON measurements (repository_id, metric_id, measured_at DESC);

-- 判定の根拠となる個別違反。比較対象 Run との差分で状態（新規 / 継続 / 解消 / 初回）を決める。
CREATE TABLE findings (
    id              uuid          PRIMARY KEY,
    run_id          uuid          NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    metric_id       varchar(8)    NOT NULL,
    fingerprint     char(64)      NOT NULL,
    state           varchar(12)   NOT NULL,
    severity        varchar(12)   NOT NULL,
    rule_id         varchar(255),
    title           varchar(512)  NOT NULL,
    file_path       varchar(1024),
    line            integer,
    component_name  varchar(64),
    detail          jsonb,
    CONSTRAINT findings_unique_key UNIQUE (run_id, fingerprint),
    CONSTRAINT findings_state_check CHECK (state IN ('NEW','CONTINUING','RESOLVED','INITIAL')),
    CONSTRAINT findings_severity_check CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW','INFO'))
);
CREATE INDEX ix_findings_run ON findings (run_id, metric_id, state);

-- 監査ログ（追記のみ）。
CREATE TABLE audit_logs (
    id             uuid        PRIMARY KEY,
    actor_user_id  uuid        REFERENCES users(id) ON DELETE SET NULL,
    actor_login    varchar(39),
    action         varchar(64) NOT NULL,
    target_type    varchar(32) NOT NULL,
    target_id      varchar(64),
    before_value   jsonb,
    after_value    jsonb,
    client_ip      inet,
    occurred_at    timestamptz NOT NULL DEFAULT now()
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

-- Spring Session JDBC のスキーマ。自動生成に任せず明示的に管理する。
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            char(36)     NOT NULL,
    SESSION_ID            char(36)     NOT NULL,
    CREATION_TIME         bigint       NOT NULL,
    LAST_ACCESS_TIME      bigint       NOT NULL,
    MAX_INACTIVE_INTERVAL int          NOT NULL,
    EXPIRY_TIME           bigint       NOT NULL,
    PRINCIPAL_NAME        varchar(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID char(36)     NOT NULL,
    ATTRIBUTE_NAME     varchar(200) NOT NULL,
    ATTRIBUTE_BYTES    bytea        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
