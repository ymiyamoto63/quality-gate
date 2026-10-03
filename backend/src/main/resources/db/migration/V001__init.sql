-- quality-gate のスキーマ（docs/architecture.md 5 章）。
-- 運用を始めた後の変更はこのファイルを書き換えず、V002 以降のマイグレーションを追加する。

-- 計測対象リポジトリ（QG_REPOSITORY の 1 行）。収集ランナーが初めて計測を送ったときに作られる。
CREATE TABLE repositories (
    id              uuid         PRIMARY KEY,
    owner           varchar(39)  NOT NULL,
    name            varchar(100) NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT repositories_full_name_key UNIQUE (owner, name)
);

-- 1 コミットに対する 1 回の計測・判定。同じコミットの再送信は attempt を増やす。
-- status は処理の状態、verdict は品質の合否（処理に失敗した Run では NULL）。
CREATE TABLE runs (
    id                   uuid         PRIMARY KEY,
    repository_id        uuid         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha           char(40)     NOT NULL,
    base_commit_sha      char(40),
    branch               varchar(255) NOT NULL,
    attempt              integer      NOT NULL DEFAULT 1,
    ci_run_url           varchar(512),
    measured_at          timestamptz  NOT NULL,
    tags                 text[]       NOT NULL DEFAULT '{}',
    baseline_run_id      uuid         REFERENCES runs(id) ON DELETE SET NULL,
    status               varchar(16)  NOT NULL,
    verdict              varchar(24),
    error_code           varchar(64),
    error_detail         text,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    evaluated_at         timestamptz,
    CONSTRAINT runs_attempt_key UNIQUE (repository_id, commit_sha, attempt),
    CONSTRAINT runs_status_check CHECK (status IN
        ('CREATED','UPLOADING','FINALIZED','PROCESSING','EVALUATED','FAILED')),
    CONSTRAINT runs_verdict_check CHECK (verdict IS NULL OR verdict IN ('PASS','FAIL'))
);
CREATE INDEX ix_runs_latest ON runs (repository_id, measured_at DESC, attempt DESC) WHERE status = 'EVALUATED';
CREATE INDEX ix_runs_list   ON runs (repository_id, branch, measured_at DESC);
CREATE INDEX ix_runs_tags   ON runs USING gin (tags);

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
    CONSTRAINT artifacts_unique_key UNIQUE (run_id, type, filename)
);

-- 指標ごとの判定結果。repository_id と measured_at は Run から複製する。
-- threshold に判定に使った合格ラインを、previous_value に比較対象 Run の値を判定時に焼き付ける。
CREATE TABLE measurements (
    id              uuid          PRIMARY KEY,
    run_id          uuid          NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    repository_id   uuid          NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    metric_id       varchar(8)    NOT NULL,
    component_name  varchar(64),
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
        ('PASS','FAIL','ERROR','NOT_APPLICABLE'))
);
-- NULL を取りうる列を含むため、一意性は COALESCE を挟んだ式インデックスで守る
CREATE UNIQUE INDEX ux_measurements_key ON measurements
    (run_id, metric_id, COALESCE(component_name, ''), COALESCE(variant, ''));
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
