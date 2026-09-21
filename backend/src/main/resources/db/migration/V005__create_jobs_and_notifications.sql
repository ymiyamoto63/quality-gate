-- DB をキューとして使う。取得は SELECT ... FOR UPDATE SKIP LOCKED。
CREATE TABLE jobs (
    id           uuid         PRIMARY KEY,
    type         varchar(32)  NOT NULL,
    dedup_key    varchar(255),
    payload      jsonb        NOT NULL,
    status       varchar(12)  NOT NULL DEFAULT 'PENDING',
    attempts     int          NOT NULL DEFAULT 0,
    max_attempts int          NOT NULL DEFAULT 5,
    run_after    timestamptz  NOT NULL DEFAULT now(),
    locked_at    timestamptz,
    locked_by    varchar(64),
    last_error   text,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT jobs_status_check CHECK (status IN
        ('PENDING','RUNNING','SUCCEEDED','FAILED','DEAD'))
);
CREATE UNIQUE INDEX ux_jobs_dedup ON jobs (type, dedup_key)
    WHERE dedup_key IS NOT NULL AND status IN ('PENDING','RUNNING');
CREATE INDEX ix_jobs_poll ON jobs (run_after) WHERE status = 'PENDING';

-- 一意制約そのものが再送の抑止になる。
CREATE TABLE notifications (
    id            uuid         PRIMARY KEY,
    run_id        uuid         REFERENCES runs(id) ON DELETE CASCADE,
    repository_id uuid         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    event         varchar(32)  NOT NULL,
    channel       varchar(16)  NOT NULL,
    status        varchar(16)  NOT NULL,
    target        varchar(255),
    external_id   varchar(255),
    error_detail  text,
    sent_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT notifications_unique_key UNIQUE (run_id, event, channel, target),
    CONSTRAINT notifications_status_check CHECK (status IN ('SENT','FAILED'))
);
