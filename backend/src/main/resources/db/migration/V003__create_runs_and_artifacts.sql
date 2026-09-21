-- 1 つのコミットに対する 1 回の計測・判定。確定後は不変。
CREATE TABLE runs (
    id                  uuid         PRIMARY KEY,
    repository_id       uuid         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha          char(40)     NOT NULL,
    base_commit_sha     char(40),
    branch              varchar(255) NOT NULL,
    pull_request_number int,
    attempt             int          NOT NULL DEFAULT 1,
    runner_type         varchar(16)  NOT NULL,
    triggered_by        varchar(64)  NOT NULL,
    ci_run_url          varchar(512),
    measured_at         timestamptz  NOT NULL,
    gate_config_id      uuid         REFERENCES gate_configs(id),
    baseline_run_id     uuid         REFERENCES runs(id) ON DELETE SET NULL,
    status              varchar(16)  NOT NULL,
    verdict             varchar(24),
    completeness        varchar(8),
    error_code          varchar(64),
    error_detail        text,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    evaluated_at        timestamptz,
    CONSTRAINT runs_attempt_key UNIQUE (repository_id, commit_sha, attempt),
    CONSTRAINT runs_status_check CHECK (status IN
        ('CREATED','UPLOADING','FINALIZED','PROCESSING','EVALUATED','FAILED','ABANDONED')),
    CONSTRAINT runs_verdict_check CHECK (verdict IS NULL OR verdict IN
        ('PASS','PASS_WITH_WARNINGS','FAIL')),
    CONSTRAINT runs_completeness_check CHECK (completeness IS NULL OR completeness IN
        ('FULL','PARTIAL')),
    CONSTRAINT runs_runner_check CHECK (runner_type IN ('self-hosted','github-hosted'))
);
CREATE INDEX ix_runs_list      ON runs (repository_id, branch, measured_at DESC);
CREATE INDEX ix_runs_retention ON runs (measured_at);

-- accepted=false は「申告したが skippable_metrics に含まれない」= 当該指標は ERROR。
CREATE TABLE run_skipped_metrics (
    run_id    uuid         NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    metric_id varchar(8)   NOT NULL,
    reason    varchar(512) NOT NULL,
    accepted  boolean      NOT NULL,
    PRIMARY KEY (run_id, metric_id)
);

-- deleted_at はファイル実体を消したことを表す。メタデータは保持期間まで残す。
CREATE TABLE artifacts (
    id             uuid         PRIMARY KEY,
    run_id         uuid         NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    type           varchar(32)  NOT NULL,
    filename       varchar(255) NOT NULL,
    size_bytes     bigint       NOT NULL,
    sha256         char(64)     NOT NULL,
    storage_key    varchar(512) NOT NULL,
    component_name varchar(64),
    scope          varchar(8),
    metadata       jsonb,
    parse_status   varchar(16)  NOT NULL DEFAULT 'PENDING',
    parse_error    text,
    uploaded_at    timestamptz  NOT NULL DEFAULT now(),
    deleted_at     timestamptz,
    CONSTRAINT artifacts_unique_key       UNIQUE (run_id, type, filename),
    CONSTRAINT artifacts_parse_status_check CHECK (parse_status IN ('PENDING','OK','FAILED')),
    CONSTRAINT artifacts_scope_check      CHECK (scope IS NULL OR scope IN ('base','head'))
);
