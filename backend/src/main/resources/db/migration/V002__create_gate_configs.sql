-- 合格ラインの設定。内容が同じなら版を増やさない（content_hash の一意制約）。
CREATE TABLE gate_configs (
    id                uuid        PRIMARY KEY,
    repository_id     uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    version           int         NOT NULL,
    source_type       varchar(16) NOT NULL,
    source_commit_sha char(40),
    content_hash      char(64)    NOT NULL,
    raw_yaml          text        NOT NULL,
    parsed            jsonb       NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT gate_configs_version_key   UNIQUE (repository_id, version),
    CONSTRAINT gate_configs_hash_key      UNIQUE (repository_id, content_hash),
    CONSTRAINT gate_configs_source_check  CHECK (source_type IN ('FILE','UI','DEFAULT'))
);
