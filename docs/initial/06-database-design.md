# quality-gate データベース設計

| 項目 | 内容 |
| --- | --- |
| ドキュメント名 | quality-gate データベース設計（基本設計） |
| バージョン | 1.0 |
| 最終更新 | 2026-09-21 |
| DBMS | PostgreSQL 17 |
| 前提文書 | [要件定義書 v1.1](01-requirements.md) / [方式設計](05-architecture.md) |

---

## 1. 設計方針

| 項目 | 方針 | 理由 |
| --- | --- | --- |
| 主キー | `uuid`（**UUIDv7** をアプリ側で採番） | API に露出する ID を推測不能にしつつ、v7 の時系列性でインデックスの局所性を保つ。採番をアプリ側で行うことで、INSERT 前に ID が確定し、関連レコードを一括構築できる |
| 命名 | テーブルは複数形の `snake_case`、列も `snake_case` | PostgreSQL の既定（小文字畳み込み）と衝突しない |
| 日時 | `timestamptz`（UTC で保存） | 表示時にタイムゾーンを適用する。`timestamp` は使わない |
| 列挙 | `varchar` + `CHECK` 制約 | PostgreSQL の `ENUM` 型は値の追加に DDL が必要で、Flyway の運用が重くなる |
| 半構造データ | `jsonb` | 指標ごとに異なる内訳（`detail`）を保持する。検索対象になる値は列に昇格させる |
| 削除 | 物理削除（保持期間バッチ） | 論理削除フラグは全クエリに条件が増え、消し忘れが積み上がる |
| 外部キー | すべて明示。削除方針も明示 | 参照整合性は DB で守る |
| 金額・割合 | `numeric` | `double precision` は丸め誤差が判定結果を変えうる |

### `numeric` を使う理由

カバレッジ 75.0% ちょうどのような境界値が、浮動小数の丸めで 74.99999 になると
判定が PASS から FAIL に変わる。判定の再現性（受け入れ基準 A-4）を守るため、
**判定に関わる数値はすべて `numeric`** とする。

---

## 2. ER 図

```
users ──┐
        │ (created_by / actor)
        ▼
repositories ──┬──▶ components
     │         ├──▶ ingest_tokens
     │         ├──▶ gate_configs
     │         └──▶ repository_summaries (1:1 読み取りモデル)
     │
     └──▶ runs ──┬──▶ artifacts
                 ├──▶ run_skipped_metrics
                 ├──▶ measurements ──▶ (component)
                 └──▶ findings

jobs            （独立。payload で他テーブルを参照）
audit_logs      （独立。追記のみ）
system_settings （独立。保持期間などのシステム設定）
```

---

## 3. テーブル定義

### 3.1 `users` — 利用者と許可リスト

ログインを許可する GitHub ユーザーの一覧そのものを兼ねる。
**このテーブルに行が無いユーザーはログインできない**（[05](05-architecture.md) 8.2）。

```sql
CREATE TABLE users (
    id              uuid        PRIMARY KEY,
    github_login    varchar(39) NOT NULL UNIQUE,   -- GitHub のユーザー名（上限 39 文字）
    github_user_id  bigint      UNIQUE,            -- 初回ログイン時に記録。追加登録時点では NULL
    display_name    varchar(255),
    avatar_url      varchar(512),
    role            varchar(16) NOT NULL DEFAULT 'VIEWER',
    status          varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_by      uuid        REFERENCES users(id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_login_at   timestamptz,
    CONSTRAINT users_role_check   CHECK (role   IN ('ADMIN','VIEWER')),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE','DISABLED'))
);
```

`github_login` を主たる識別子にしつつ `github_user_id` も保持するのは、
**GitHub のユーザー名は変更できる**ためである。初回ログイン時に不変の ID を控え、
以後の照合はそちらを優先する。管理者が事前登録する時点では名前しか分からないため、
`github_user_id` は NULL を許す。

### 3.2 `repositories` — 計測対象リポジトリ

```sql
CREATE TABLE repositories (
    id              uuid        PRIMARY KEY,
    owner           varchar(39) NOT NULL,
    name            varchar(100) NOT NULL,
    default_branch  varchar(255) NOT NULL DEFAULT 'main',
    measure_pull_requests boolean NOT NULL DEFAULT true,
    enabled         boolean     NOT NULL DEFAULT true,
    created_by      uuid        NOT NULL REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT repositories_full_name_key UNIQUE (owner, name)
);
```

### 3.3 `components` — リポジトリ内の構成単位

```sql
CREATE TABLE components (
    id              uuid        PRIMARY KEY,
    repository_id   uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    name            varchar(64) NOT NULL,          -- 'backend' / 'frontend'
    language        varchar(32) NOT NULL,          -- 'java' / 'typescript'
    path_patterns   jsonb       NOT NULL,          -- ["backend/**"]
    display_order   int         NOT NULL DEFAULT 0,
    CONSTRAINT components_name_key UNIQUE (repository_id, name)
);
```

### 3.4 `ingest_tokens` — 取り込み用トークン

```sql
CREATE TABLE ingest_tokens (
    id              uuid        PRIMARY KEY,
    repository_id   uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    token_prefix    varchar(8)  NOT NULL UNIQUE,   -- 検索用。秘密ではない
    token_hash      char(64)    NOT NULL,          -- SHA-256（16 進）
    description     varchar(255),
    created_by      uuid        NOT NULL REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz,
    revoked_at      timestamptz
);
CREATE INDEX ix_ingest_tokens_repo ON ingest_tokens (repository_id) WHERE revoked_at IS NULL;
```

`token_prefix` にのみインデックスを張り、`token_hash` は取得後に
**定数時間比較**する（[05](05-architecture.md) 8.3）。
ハッシュで検索すると全件走査になるうえ、比較時間から情報が漏れうる。

### 3.5 `gate_configs` — 合格ラインの設定（版管理）

```sql
CREATE TABLE gate_configs (
    id              uuid        PRIMARY KEY,
    repository_id   uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    version         int         NOT NULL,          -- リポジトリ内の連番
    source_type     varchar(16) NOT NULL,          -- 'FILE' / 'UI' / 'DEFAULT'
    source_commit_sha char(40),                    -- FILE の場合の取得元
    content_hash    char(64)    NOT NULL,          -- 正規化後の内容の SHA-256
    raw_yaml        text        NOT NULL,
    parsed          jsonb       NOT NULL,          -- 検証済みの構造化表現
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT gate_configs_version_key UNIQUE (repository_id, version),
    CONSTRAINT gate_configs_hash_key    UNIQUE (repository_id, content_hash),
    CONSTRAINT gate_configs_source_check CHECK (source_type IN ('FILE','UI','DEFAULT'))
);
```

`content_hash` に一意制約を置くことで、**内容が同じ設定は版を増やさない**。
毎回の Run で新しい版が作られると、変更履歴がノイズで埋まる。

### 3.6 `runs` — 計測・判定の単位

```sql
CREATE TABLE runs (
    id                  uuid        PRIMARY KEY,
    repository_id       uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha          char(40)    NOT NULL,
    base_commit_sha     char(40),
    branch              varchar(255) NOT NULL,
    pull_request_number int,
    attempt             int         NOT NULL DEFAULT 1,
    triggered_by        varchar(64) NOT NULL,
    ci_run_url          varchar(512),
    measured_at         timestamptz NOT NULL,
    gate_config_id      uuid        REFERENCES gate_configs(id),
    baseline_run_id     uuid        REFERENCES runs(id) ON DELETE SET NULL,
    status              varchar(16) NOT NULL,
    verdict             varchar(24),
    completeness        varchar(8),
    error_code          varchar(64),
    error_detail        text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    evaluated_at        timestamptz,
    CONSTRAINT runs_attempt_key UNIQUE (repository_id, commit_sha, attempt),
    CONSTRAINT runs_status_check CHECK (status IN
        ('CREATED','UPLOADING','FINALIZED','PROCESSING','EVALUATED','FAILED','ABANDONED')),
    CONSTRAINT runs_verdict_check CHECK (verdict IS NULL OR verdict IN
        ('PASS','PASS_WITH_WARNINGS','FAIL')),
    CONSTRAINT runs_completeness_check CHECK (completeness IS NULL OR completeness IN
        ('FULL','PARTIAL'))
);
```

ランナー種別の列（`runner_type`）は、計測を収集ランナーに絞ったため V016 で削除した（D-19）。

`baseline_run_id` を**保存する**のが要点である。差分（NEW / CONTINUING / RESOLVED）が
どの Run との比較で出たものかを後から追えるようにし、判定の再現性を保つ。

### 3.7 `run_skipped_metrics` — スキップ申告

```sql
CREATE TABLE run_skipped_metrics (
    run_id      uuid        NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    metric_id   varchar(8)  NOT NULL,       -- 'M-02'
    reason      varchar(512) NOT NULL,
    accepted    boolean     NOT NULL,       -- skippable_metrics に含まれ SKIP となったか
    PRIMARY KEY (run_id, metric_id)
);
```

`accepted = false` は「スキップを申告したが許容されていない」状態であり、
当該指標は `ERROR` になる。申告の事実自体は記録に残し、
なぜ ERROR になったのかを Run 詳細で説明できるようにする。

### 3.8 `artifacts` — 取り込んだ成果物

```sql
CREATE TABLE artifacts (
    id              uuid        PRIMARY KEY,
    run_id          uuid        NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    type            varchar(32) NOT NULL,       -- 'jacoco-xml' など
    filename        varchar(255) NOT NULL,
    size_bytes      bigint      NOT NULL,
    sha256          char(64)    NOT NULL,
    storage_key     varchar(512) NOT NULL,      -- ArtifactStore 内の相対パス
    component_name  varchar(64),
    scope           varchar(8),                 -- 'base' / 'head'（M-07 のベース比較用）
    metadata        jsonb,                      -- 性能計測の environment など
    parse_status    varchar(16) NOT NULL DEFAULT 'PENDING',
    parse_error     text,
    uploaded_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,                -- 保持期間経過でファイルのみ削除
    CONSTRAINT artifacts_unique_key UNIQUE (run_id, type, filename),
    CONSTRAINT artifacts_parse_status_check CHECK (parse_status IN ('PENDING','OK','FAILED'))
);
```

`deleted_at` はファイル実体を消したことを表す。**メタデータは残す**ため、
「このとき何を取り込んだか」は保持期間を過ぎても追える。

### 3.9 `measurements` — 指標ごとの実測値と判定

```sql
CREATE TABLE measurements (
    id              uuid        PRIMARY KEY,
    run_id          uuid        NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    repository_id   uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    component_id    uuid        REFERENCES components(id) ON DELETE SET NULL,
    metric_id       varchar(8)  NOT NULL,
    component_name  varchar(64),                -- 'backend' / 'frontend'（V008 で追加）
    scenario        varchar(64),                -- M-03 のシナリオ単位判定用
    variant         varchar(16),                -- 計測条件。M-02 の実行範囲 changed / all（V010 で追加）
    status          varchar(16) NOT NULL,       -- NOT_APPLICABLE を入れるため V010 で 12 → 16
    value           numeric(12,4),
    unit            varchar(16),
    threshold       jsonb,                      -- {"operator":">=","value":75}
    previous_value  numeric(12,4),
    reason          varchar(512),               -- 判定理由（そのまま画面に出す）
    detail          jsonb,                      -- 分母分子などの内訳
    measured_at     timestamptz NOT NULL,       -- runs.measured_at の複製（トレンド検索用）
    CONSTRAINT measurements_status_check CHECK (status IN
        ('PASS','WARN','FAIL','SKIP','REFERENCE','ERROR','NOT_APPLICABLE'))
);

-- component_name / scenario / variant は NULL を取りうる。UNIQUE 制約では NULL 同士が
-- 重複と見なされないため、COALESCE を挟んだ一意インデックスで担保する。
CREATE UNIQUE INDEX ux_measurements_key ON measurements
    (run_id, metric_id, COALESCE(component_name, ''), COALESCE(scenario, ''),
     COALESCE(variant, ''));
```

`variant` は**値どうしを比較できるかを分ける計測条件**である。M-02 は実行範囲
（変更クラスのみ / 全量）で値の意味が変わり、両者を比べた差は品質の変化ではなく
範囲の違いを表すだけになる。そのため前回値（`previous_value`）は `variant` の
一致する行からだけ引き、トレンドの系列も `variant` ごとに分ける。
`detail`（jsonb）に入れず列にするのは、トレンド検索で系列の軸として使うためである。

`component_name` を持たせるのは、表示とトレンドの絞り込みで常に必要になるためである。
`findings` が既に `component_name` を非正規化して持っており、
片方は FK を辿り片方は文字列という不整合な扱いを避ける。
`component_id` は将来のコンポーネント管理機能のために残す。

一意性を UNIQUE 制約ではなく式インデックスで担保するのは、
**SQL の UNIQUE 制約が NULL 同士を重複と見なさない**ためである。
`component_name` が NULL のまま UNIQUE 制約に任せると、
同一 Run・同一指標の行が何行でも入ってしまう。

`repository_id` と `measured_at` を `runs` から**意図的に複製**している。
トレンド API（NFR 10.1 で p95 800ms）は期間とリポジトリと指標で絞り込むため、
毎回 `runs` と結合すると要件を満たしにくい。
更新されない値の複製であり、不整合が生じる余地がない箇所に限って許容する。

### 3.10 `findings` — 個別の違反

```sql
CREATE TABLE findings (
    id              uuid        PRIMARY KEY,
    run_id          uuid        NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    metric_id       varchar(8)  NOT NULL,
    fingerprint     char(64)    NOT NULL,
    state           varchar(12) NOT NULL,       -- NEW / CONTINUING / RESOLVED / INITIAL
    severity        varchar(12) NOT NULL,       -- CRITICAL / HIGH / MEDIUM / LOW / INFO
    rule_id         varchar(255),
    title           varchar(512) NOT NULL,
    file_path       varchar(1024),
    line            int,
    component_name  varchar(64),
    detail          jsonb,                      -- CVE ID、CC 値、axe の impact など
    CONSTRAINT findings_state_check CHECK (state IN
        ('NEW','CONTINUING','RESOLVED','INITIAL')),
    CONSTRAINT findings_severity_check CHECK (severity IN
        ('CRITICAL','HIGH','MEDIUM','LOW','INFO')),
    CONSTRAINT findings_unique_key UNIQUE (run_id, fingerprint)
);
```

V017 で `waiver_id`（免除の紐付け）を削除した（D-22）。

### 3.11 `waivers` — 免除（V017 で削除）

免除（D-12）を廃止したため、V017 でテーブルごと削除した（D-22）。

### 3.12 `notifications` — 通知の送信履歴（V017 で削除）

メール通知を廃止したため、V017 でテーブルごと削除した（D-22）。

### 3.13 `jobs` — ジョブキュー

```sql
CREATE TABLE jobs (
    id            uuid        PRIMARY KEY,
    type          varchar(32) NOT NULL,
    dedup_key     varchar(255),
    payload       jsonb       NOT NULL,
    status        varchar(12) NOT NULL DEFAULT 'PENDING',
    attempts      int         NOT NULL DEFAULT 0,
    max_attempts  int         NOT NULL DEFAULT 5,
    run_after     timestamptz NOT NULL DEFAULT now(),
    locked_at     timestamptz,
    locked_by     varchar(64),
    last_error    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT jobs_status_check CHECK (status IN
        ('PENDING','RUNNING','SUCCEEDED','FAILED','DEAD'))
);
CREATE UNIQUE INDEX ux_jobs_dedup ON jobs (type, dedup_key)
    WHERE dedup_key IS NOT NULL AND status IN ('PENDING','RUNNING');
CREATE INDEX ix_jobs_poll ON jobs (run_after) WHERE status = 'PENDING';
```

取得クエリ:

```sql
SELECT * FROM jobs
 WHERE status = 'PENDING' AND run_after <= now()
 ORDER BY run_after
 LIMIT 4
 FOR UPDATE SKIP LOCKED;
```

### 3.14 `repository_summaries` — ダッシュボード用の読み取りモデル

```sql
CREATE TABLE repository_summaries (
    repository_id       uuid        PRIMARY KEY REFERENCES repositories(id) ON DELETE CASCADE,
    latest_run_id       uuid        REFERENCES runs(id) ON DELETE SET NULL,
    latest_verdict      varchar(24),
    latest_completeness varchar(8),
    latest_measured_at  timestamptz,
    last_full_run_id    uuid        REFERENCES runs(id) ON DELETE SET NULL,
    last_full_measured_at timestamptz,
    category_status     jsonb,      -- {"機能テスト":"PASS","性能テスト":"REFERENCE",...}
    open_critical_count int         NOT NULL DEFAULT 0,
    open_high_count     int         NOT NULL DEFAULT 0,
    version             bigint      NOT NULL DEFAULT 0,   -- 楽観ロック（9 章）
    updated_at          timestamptz NOT NULL DEFAULT now()
);
```

判定完了時に更新する。
ダッシュボードはこの 1 テーブルを読むだけで描画でき、
Run や Measurement を走査しない（[05](05-architecture.md) 11 章）。

`last_full_measured_at` は最後の完全計測の日時として画面に常に表示する（FR-06-3）。

### 3.15 `audit_logs` — 監査ログ

```sql
CREATE TABLE audit_logs (
    id            uuid        PRIMARY KEY,
    actor_user_id uuid        REFERENCES users(id) ON DELETE SET NULL,
    actor_login   varchar(39),                -- ユーザー削除後も誰の操作か残す
    action        varchar(64) NOT NULL,       -- 'REPOSITORY_CREATED' / 'USER_ADDED' / ...
    target_type   varchar(32) NOT NULL,
    target_id     varchar(64),
    before_value  jsonb,
    after_value   jsonb,
    client_ip     inet,
    occurred_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_logs_occurred ON audit_logs (occurred_at DESC);
CREATE INDEX ix_audit_logs_target   ON audit_logs (target_type, target_id);
```

**追記専用**とするため、アプリケーションが使う DB ロールから
`UPDATE` / `DELETE` 権限を剥奪する。

```sql
REVOKE UPDATE, DELETE ON audit_logs FROM quality_gate_app;
```

V006 はロール `quality_gate_app` が存在する場合だけこれを実行する。開発環境（所有者ロールで接続）では剥奪されない。

保持期間の削除は、別の管理ロールで実行するバッチが行う。アプリの日次バッチも削除を試みるが、
権限が剥奪された環境では警告を残して何もしない。
アプリの実装ミスで監査ログが書き換わる経路を、権限の側で塞ぐ。

`actor_login` を非正規化しているのは、利用者を削除しても
「誰が操作したか」が失われないようにするため。

### 3.16 `notification_settings` — リポジトリごとの通知設定（V017 で削除）

メール通知を廃止したため、V017 でテーブルごと削除した（D-22）。

### 3.17 `system_settings` — システム全体の設定

```sql
CREATE TABLE system_settings (
    key        varchar(64) PRIMARY KEY,
    value      jsonb       NOT NULL,
    updated_by uuid        REFERENCES users(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

保持期間（7 章）をキーごとに JSON で持つ。行が無ければ 7 章の既定値を使う。

### 3.18 Spring Session

`spring-session-jdbc` が提供する `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` を使う。
DDL は Spring Session の配布物をそのまま Flyway マイグレーションに取り込む
（自動生成に任せず、スキーマ変更を明示的に管理するため）。

---

## 4. 状態と列挙値の一覧

| 対象 | 値 |
| --- | --- |
| `runs.status` | `CREATED` / `UPLOADING` / `FINALIZED` / `PROCESSING` / `EVALUATED` / `FAILED` / `ABANDONED` |
| `runs.verdict` | `PASS` / `PASS_WITH_WARNINGS` / `FAIL` |
| `runs.completeness` | `FULL` / `PARTIAL` |
| `measurements.status` | `PASS` / `WARN` / `FAIL` / `SKIP` / `REFERENCE` / `ERROR` / `NOT_APPLICABLE` |
| `findings.state` | `NEW` / `CONTINUING` / `RESOLVED` / `INITIAL` |
| `findings.severity` | `CRITICAL` / `HIGH` / `MEDIUM` / `LOW` / `INFO` |
| `users.role` | `ADMIN` / `VIEWER` |
| `jobs.status` | `PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `DEAD` |

---

## 5. インデックス設計

主要なクエリパターンから逆算して張る。

| # | クエリ | インデックス |
| --- | --- | --- |
| 1 | ダッシュボード（全リポジトリのサマリ） | `repository_summaries` の PK のみ。走査対象が数行のため追加不要 |
| 2 | Run 一覧（リポジトリ・ブランチ・新しい順） | `ix_runs_list ON runs (repository_id, branch, measured_at DESC)` |
| 3 | Run 詳細の指標一覧 | `ix_measurements_run ON measurements (run_id)` |
| 4 | **トレンド**（リポジトリ × 指標 × 期間） | `ix_measurements_trend ON measurements (repository_id, metric_id, measured_at DESC)` |
| 5 | Finding 一覧（Run 内・状態や深刻度で絞る） | `ix_findings_run ON findings (run_id, metric_id, state)` |
| 6 | ジョブ取得 | `ix_jobs_poll`（3.13） |
| 7 | Ingest Token 照合 | `token_prefix` の UNIQUE 制約 |
| 8 | 保持期間の削除対象抽出 | `ix_runs_retention ON runs (measured_at)` |

4 のインデックスが最も重要である。トレンドは 30 日 × 1 指標で
数十〜数百行を返すだけだが、`measurements` は 3 年で数百万行になる。
`repository_id` と `metric_id` で絞り込めないと、期間指定だけでは足りない。

### 部分インデックスを使う箇所

```sql
-- 有効なトークンだけを対象にする
CREATE INDEX ix_ingest_tokens_repo ON ingest_tokens (repository_id) WHERE revoked_at IS NULL;
-- 実行待ちジョブだけを対象にする
CREATE INDEX ix_jobs_poll ON jobs (run_after) WHERE status = 'PENDING';
```

いずれも「大半が対象外」のテーブルであり、部分インデックスにすることで
サイズを小さく保てる。ジョブは成功後も履歴として残るため、
全体では増え続けるが、実行待ちは常に数件である。

---

## 6. データ量の見積もり

[要件定義書 10.2](01-requirements.md) の 3 年後想定（5 リポジトリ / 100 Run・日）で試算する。

| テーブル | 行数（3 年） | 備考 |
| --- | --- | --- |
| `runs` | 約 11 万 | 100/日 × 365 × 3 |
| `measurements` | 約 220 万 | 1 Run あたり約 20 行（指標 × コンポーネント × シナリオ） |
| `findings` | 約 1,100 万 | 1 Run あたり約 100 行（RESOLVED を含むため多め） |
| `artifacts` | 約 110 万 | 1 Run あたり約 10 件。ファイル実体は 90 日で削除 |
| `audit_logs` | 約 10 万 | |

`findings` が最大になる。RESOLVED を保存する方式（[05](05-architecture.md) 6.3）の
代償だが、1,100 万行は PostgreSQL にとって大きな負荷ではなく、
適切なインデックスがあれば問題にならない規模である。

将来さらに桁が増えた場合は、`findings` と `measurements` を
`measured_at` による**レンジパーティション**（年単位）に移行する。
そのため、この 2 テーブルには `measured_at` 相当の列を最初から持たせてある。

---

## 7. 保持期間と削除

下表の保持期間は既定値である。Run・成果物・監査ログの日数は管理画面（S-09）から変更でき、
`system_settings` に保存する。

| 対象 | 保持期間 | 削除方法 |
| --- | --- | --- |
| 成果物のファイル実体 | 90 日 | ファイルを削除し `artifacts.deleted_at` を設定 |
| `artifacts` の行 | 2 年 | Run とともに削除 |
| `runs` / `measurements` / `findings` | 2 年 | `runs` を削除し、`ON DELETE CASCADE` で連鎖 |
| `audit_logs` | 2 年 | 管理ロールのバッチで削除 |
| `jobs`（`SUCCEEDED`） | 30 日 | |
| `jobs`（`DEAD`） | 無期限 | 手動で確認・削除する |

削除は日次バッチで**少量ずつ**実行する（1 回あたり最大 10,000 行）。
一括削除は長時間のロックと WAL の急増を招き、その間アプリが停止する。

```sql
DELETE FROM runs
 WHERE id IN (
     SELECT id FROM runs
      WHERE measured_at < now() - interval '2 years'
      ORDER BY measured_at
      LIMIT 10000
 );
```

---

## 8. Flyway 運用規約

| 項目 | 規約 |
| --- | --- |
| 配置 | `backend/src/main/resources/db/migration/` |
| 命名 | `V<連番3桁>__<snake_case の説明>.sql`（例: `V001__create_core_tables.sql`） |
| 適用済みファイル | **絶対に変更しない**。修正は新しいマイグレーションで行う |
| ロールバック | Flyway の undo は使わない。前方向の修正マイグレーションのみ |
| 破壊的変更 | 列削除・型変更は 2 段階（新列追加 → 移行 → 旧列削除）で行う |
| テストデータ | マイグレーションに含めない。`V900__` 台の開発用プロファイル限定スクリプトに分ける |
| 検証 | Testcontainers で全マイグレーションを空の DB に適用するテストを持つ |

### 初期マイグレーションの構成

| ファイル | 内容 |
| --- | --- |
| `V001__create_users_and_repositories.sql` | `users` / `repositories` / `components` / `ingest_tokens` |
| `V002__create_gate_configs.sql` | `gate_configs` |
| `V003__create_runs_and_artifacts.sql` | `runs` / `run_skipped_metrics` / `artifacts` |
| `V004__create_measurements_findings_waivers.sql` | `measurements` / `findings` / `waivers` |
| `V005__create_jobs_and_notifications.sql` | `jobs` / `notifications` |
| `V006__create_summaries_and_audit.sql` | `repository_summaries` / `audit_logs` と権限設定 |
| `V007__create_spring_session.sql` | Spring Session JDBC のテーブル |
| `V008__add_measurement_component_name.sql` | `measurements.component_name` の追加と一意インデックスの置き換え |
| `V009__skipped_metric_acceptance_at_evaluation.sql` | `run_skipped_metrics.accepted` を NULL 許容にし、受理の可否を判定時に決める |
| `V010__measurement_variant_and_not_applicable.sql` | `measurements.variant` の追加、一意インデックスの置き換え、`NOT_APPLICABLE` の追加と `status` の拡幅 |
| `V011__widen_measurement_variant.sql` | `measurements.variant` を 64 文字に拡幅（性能指標の計測環境名を入れるため） |
| `V012__create_notification_and_system_settings.sql` | `notification_settings`（リポジトリごとの通知条件と宛先）・`system_settings`（保持期間など）の追加、`notifications.dedup_key`（Run を持たない通知の重複抑止） |
| `V013__waiver_title_and_run_status.sql` | `waivers.title`（登録時点の違反の見出し）、`runs.previous_verdict`（再評価の直前の判定。通知の遷移判定に使う） |
| `V014__email_only_notifications.sql` | 通知をメールのみにしたため、V012 の Slack・PR コメントの列を削除（D-15） |
| `V015__run_renamed_files.sql` | `runs.renamed_files`（ファイルの移動・リネームの対応表） |
| `V016__collector_only_ingest.sql` | 取り込み経路を収集ランナーに絞ったため、`runs.runner_type` と Check Run のジョブ、設定の削除したキーを取り除く（D-19） |
| `V017__drop_waivers_and_notifications.sql` | 免除と通知を廃止したため、`waivers` / `notifications` / `notification_settings`・`findings.waiver_id`・`repository_summaries.active_waiver_count`・`runs.previous_verdict` と、処理する側の無いジョブ、保存済みの設定の `notifications` / `full_measurement_interval_days` を削除する（D-22） |

`findings.waiver_id` の外部キーは `V004` で `waivers` を先に作って張った（V017 で列ごと削除）。

---

## 9. JPA マッピング方針

| 項目 | 方針 |
| --- | --- |
| ID 採番 | `@Id` にアプリ生成の UUIDv7 を設定。`@GeneratedValue` は使わない |
| 列挙 | `@Enumerated(EnumType.STRING)`。序数は使わない（値の追加で既存データの意味が変わるため） |
| `jsonb` | `@JdbcTypeCode(SqlTypes.JSON)` |
| 関連 | すべて `FetchType.LAZY`。`OneToMany` は原則マッピングせず、リポジトリのクエリで取得する |
| 一括 INSERT | 判定結果の保存は `measurements` / `findings` とも JDBC バッチ（`hibernate.jdbc.batch_size=100`） |
| 参照系 | エンティティを返さず、**専用の DTO へ射影**する（`SELECT new ...` またはインタフェース射影） |
| 更新検知 | `@Version` による楽観ロックは `repository_summaries` にのみ適用 |

`OneToMany` をマッピングしない方針は、N+1 問題と、
意図しない遅延ロードの発生を構造的に避けるため。
必要な形のデータは、その都度クエリで明示的に取得する。

参照系で DTO 射影を使うのは、**ダッシュボードとトレンドが必要とするのは
エンティティ全体ではない**ためである。エンティティを返すと不要な列まで読み、
遅延ロードの誘発点になる。
