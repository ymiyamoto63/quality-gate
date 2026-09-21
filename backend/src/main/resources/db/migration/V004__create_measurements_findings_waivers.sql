-- 免除。expires_at を NOT NULL にすることで、無期限の免除を構造的に作れなくする。
CREATE TABLE waivers (
    id              uuid        PRIMARY KEY,
    repository_id   uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    scope           varchar(12) NOT NULL,
    metric_id       varchar(8)  NOT NULL,
    fingerprint     char(64),
    reason_category varchar(32) NOT NULL,
    reason          text        NOT NULL,
    status          varchar(12) NOT NULL DEFAULT 'ACTIVE',
    created_by      uuid        NOT NULL REFERENCES users(id),
    approved_by     uuid        REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    revoked_by      uuid        REFERENCES users(id),
    CONSTRAINT waivers_scope_check  CHECK (scope  IN ('FINDING','METRIC')),
    CONSTRAINT waivers_status_check CHECK (status IN ('ACTIVE','EXPIRED','REVOKED')),
    CONSTRAINT waivers_reason_category_check CHECK (reason_category IN
        ('UNREACHABLE','FALSE_POSITIVE','NO_FIX_AVAILABLE','PLANNED')),
    CONSTRAINT waivers_fingerprint_required CHECK (scope <> 'FINDING' OR fingerprint IS NOT NULL),
    CONSTRAINT waivers_expiry_required CHECK (expires_at > created_at)
);
-- 同じ違反に対する有効な免除は常に 1 件に保つ
CREATE UNIQUE INDEX ux_waivers_active ON waivers (repository_id, metric_id, fingerprint)
    WHERE status = 'ACTIVE' AND scope = 'FINDING';
CREATE INDEX ix_waivers_expiry ON waivers (expires_at) WHERE status = 'ACTIVE';

-- repository_id と measured_at は runs からの意図的な複製（トレンド検索のため）。
CREATE TABLE measurements (
    id             uuid         PRIMARY KEY,
    run_id         uuid         NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    repository_id  uuid         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    component_id   uuid         REFERENCES components(id) ON DELETE SET NULL,
    metric_id      varchar(8)   NOT NULL,
    scenario       varchar(64),
    status         varchar(12)  NOT NULL,
    value          numeric(12,4),
    unit           varchar(16),
    threshold      jsonb,
    previous_value numeric(12,4),
    reason         varchar(512),
    detail         jsonb,
    measured_at    timestamptz  NOT NULL,
    CONSTRAINT measurements_status_check CHECK (status IN
        ('PASS','WARN','FAIL','SKIP','REFERENCE','ERROR'))
);
-- component_id / scenario は NULL を取りうるため、一意性は部分インデックスで担保する
CREATE UNIQUE INDEX ux_measurements_key ON measurements
    (run_id, metric_id, COALESCE(component_id, '00000000-0000-0000-0000-000000000000'::uuid),
     COALESCE(scenario, ''));
CREATE INDEX ix_measurements_run   ON measurements (run_id);
CREATE INDEX ix_measurements_trend ON measurements (repository_id, metric_id, measured_at DESC);

CREATE TABLE findings (
    id             uuid         PRIMARY KEY,
    run_id         uuid         NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    metric_id      varchar(8)   NOT NULL,
    fingerprint    char(64)     NOT NULL,
    state          varchar(12)  NOT NULL,
    severity       varchar(12)  NOT NULL,
    rule_id        varchar(255),
    title          varchar(512) NOT NULL,
    file_path      varchar(1024),
    line           int,
    component_name varchar(64),
    detail         jsonb,
    waiver_id      uuid         REFERENCES waivers(id) ON DELETE SET NULL,
    CONSTRAINT findings_state_check    CHECK (state    IN ('NEW','CONTINUING','RESOLVED','INITIAL')),
    CONSTRAINT findings_severity_check CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW','INFO')),
    CONSTRAINT findings_unique_key     UNIQUE (run_id, fingerprint)
);
CREATE INDEX ix_findings_run ON findings (run_id, metric_id, state);
