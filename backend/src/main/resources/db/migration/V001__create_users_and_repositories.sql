-- 利用者と許可リスト（docs/06-database-design.md 3.1）
-- このテーブルに行が無い GitHub ユーザーはログインできない。
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

CREATE TABLE repositories (
    id                    uuid         PRIMARY KEY,
    owner                 varchar(39)  NOT NULL,
    name                  varchar(100) NOT NULL,
    default_branch        varchar(255) NOT NULL DEFAULT 'main',
    measure_pull_requests boolean      NOT NULL DEFAULT true,
    enabled               boolean      NOT NULL DEFAULT true,
    created_by            uuid         NOT NULL REFERENCES users(id),
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT repositories_full_name_key UNIQUE (owner, name)
);

CREATE TABLE components (
    id            uuid        PRIMARY KEY,
    repository_id uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    name          varchar(64) NOT NULL,
    language      varchar(32) NOT NULL,
    path_patterns jsonb       NOT NULL,
    display_order int         NOT NULL DEFAULT 0,
    CONSTRAINT components_name_key UNIQUE (repository_id, name)
);

-- token_prefix にのみインデックスを張り、token_hash は取得後に定数時間比較する。
CREATE TABLE ingest_tokens (
    id            uuid         PRIMARY KEY,
    repository_id uuid         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    token_prefix  varchar(8)   NOT NULL UNIQUE,
    token_hash    char(64)     NOT NULL,
    description   varchar(255),
    created_by    uuid         NOT NULL REFERENCES users(id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_used_at  timestamptz,
    revoked_at    timestamptz
);
CREATE INDEX ix_ingest_tokens_repo ON ingest_tokens (repository_id) WHERE revoked_at IS NULL;
