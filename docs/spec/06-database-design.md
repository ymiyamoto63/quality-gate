# quality-gate データベース設計

本書は quality-gate のテーブル定義と、その設計の理由を定める（DBMS は PostgreSQL 17）。
前提は [要件定義書](01-requirements.md) と [方式設計](05-architecture.md)。

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
repositories ──▶ gate_configs
     │
     └──▶ runs ──┬──▶ artifacts
                 ├──▶ run_skipped_metrics
                 ├──▶ measurements
                 └──▶ findings

audit_logs      （独立。追記のみ）
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
    enabled         boolean     NOT NULL DEFAULT true,
    created_by      uuid        NOT NULL REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT repositories_full_name_key UNIQUE (owner, name)
);
```

PR を計測するかは、収集ランナーの手動実行で PR 番号を指定するかどうかで決まる。

### 3.3 `gate_configs` — 合格ラインの設定（版管理）

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
毎回の Run で新しい版が作られると、同じ合格ラインの版が Run の数だけ増える。

### 3.4 `runs` — 計測・判定の単位

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
    tags                text[]      NOT NULL DEFAULT '{}',  -- 計測したコミットを指すタグ
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

`tags` は収集ランナーが計測時に対象の履歴から求めて送る（`git tag --points-at`）。リリース判定（S-09）でタグを
コミットに解決するのに使い、GIN 索引（`ix_runs_tags`）で引く。

`baseline_run_id` を**保存する**のが要点である。差分（NEW / CONTINUING / RESOLVED）が
どの Run との比較で出たものかを後から追えるようにし、判定の再現性を保つ。

### 3.5 `run_skipped_metrics` — スキップ申告

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

### 3.6 `artifacts` — 取り込んだ成果物

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

### 3.7 `measurements` — 指標ごとの実測値と判定

```sql
CREATE TABLE measurements (
    id              uuid        PRIMARY KEY,
    run_id          uuid        NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    repository_id   uuid        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    metric_id       varchar(8)  NOT NULL,
    component_name  varchar(64),                -- 'backend' / 'frontend'
    scenario        varchar(64),                -- M-03 のシナリオ単位判定用
    variant         varchar(64),                -- 計測条件。M-02 の実行範囲 changed / all、性能指標の計測環境名
    status          varchar(16) NOT NULL,
    value           numeric(12,4),
    unit            varchar(16),
    threshold       jsonb,                      -- {"operator":">=","value":75}
    previous_value  numeric(12,4),
    reason          varchar(512),               -- 判定理由（そのまま画面に出す）
    detail          jsonb,                      -- 分母分子などの内訳
    measured_at     timestamptz NOT NULL,       -- runs.measured_at の複製（トレンド検索用）
    CONSTRAINT measurements_status_check CHECK (status IN
        ('PASS','WARN','FAIL','SKIP','ERROR','NOT_APPLICABLE'))
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
コンポーネントは計測プロファイルで決まり、成果物に付いた名前をそのまま入れる（[03](03-design-decisions.md) DD-9）。

一意性を UNIQUE 制約ではなく式インデックスで担保するのは、
**SQL の UNIQUE 制約が NULL 同士を重複と見なさない**ためである。
`component_name` が NULL のまま UNIQUE 制約に任せると、
同一 Run・同一指標の行が何行でも入ってしまう。

`repository_id` と `measured_at` を `runs` から**意図的に複製**している。
トレンド API（NFR 10.1 で p95 800ms）は期間とリポジトリと指標で絞り込むため、
毎回 `runs` と結合すると要件を満たしにくい。
更新されない値の複製であり、不整合が生じる余地がない箇所に限って許容する。

### 3.8 `findings` — 個別の違反

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

### 3.9 `audit_logs` — 監査ログ

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

マイグレーションは、ロール `quality_gate_app` が存在する場合だけこれを実行する。開発環境（所有者ロールで接続）では剥奪されない。

保持期間の削除は、別の管理ロールで実行するバッチが行う。アプリの日次バッチも削除を試みるが、
権限が剥奪された環境では警告を残して何もしない。
アプリの実装ミスで監査ログが書き換わる経路を、権限の側で塞ぐ。

`actor_login` を非正規化しているのは、利用者を削除しても
「誰が操作したか」が失われないようにするため。

### 3.10 Spring Session

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
| `measurements.status` | `PASS` / `WARN` / `FAIL` / `SKIP` / `ERROR` / `NOT_APPLICABLE` |
| `findings.state` | `NEW` / `CONTINUING` / `RESOLVED` / `INITIAL` |
| `findings.severity` | `CRITICAL` / `HIGH` / `MEDIUM` / `LOW` / `INFO` |
| `users.role` | `ADMIN` / `VIEWER` |

---

## 5. インデックス設計

主要なクエリパターンから逆算して張る。

| # | クエリ | インデックス |
| --- | --- | --- |
| 1 | ダッシュボード・リポジトリ詳細（リポジトリごとの最新の判定済み Run と最後の完全計測） | `ix_runs_latest ON runs (repository_id, measured_at DESC, attempt DESC) WHERE status = 'EVALUATED'` |
| 2 | Run 一覧（リポジトリ・ブランチ・新しい順） | `ix_runs_list ON runs (repository_id, branch, measured_at DESC)` |
| 3 | Run 詳細の指標一覧 | `ix_measurements_run ON measurements (run_id)` |
| 4 | **トレンド**（リポジトリ × 指標 × 期間） | `ix_measurements_trend ON measurements (repository_id, metric_id, measured_at DESC)` |
| 5 | Finding 一覧（Run 内・状態や深刻度で絞る） | `ix_findings_run ON findings (run_id, metric_id, state)` |
| 6 | 保持期間の削除対象抽出 | `ix_runs_retention ON runs (measured_at)` |
| 7 | リリース判定のタグの解決 | `ix_runs_tags ON runs USING GIN (tags)` |

4 のインデックスが最も重要である。トレンドは 30 日 × 1 指標で
数十〜数百行を返すだけだが、`measurements` は 3 年で数百万行になる。
`repository_id` と `metric_id` で絞り込めないと、期間指定だけでは足りない。

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

下表の保持期間は既定値である。Run・成果物・監査ログの日数は環境変数
（`QG_RETENTION_RUN_DAYS` / `QG_RETENTION_ARTIFACT_DAYS` / `QG_RETENTION_AUDIT_LOG_DAYS`）で変えられる。
変える頻度がほとんど無いため、画面からは変更しない。誤って短い日数を設定すると大半のデータが消えるため、
下限（Run 30 日・成果物 1 日・監査ログ 365 日）を下回る値ではアプリが起動しない。

| 対象 | 保持期間 | 削除方法 |
| --- | --- | --- |
| 成果物のファイル実体 | 90 日 | ファイルを削除し `artifacts.deleted_at` を設定 |
| `artifacts` の行 | 2 年 | Run とともに削除 |
| `runs` / `measurements` / `findings` | 2 年 | `runs` を削除し、`ON DELETE CASCADE` で連鎖 |
| `audit_logs` | 2 年 | 管理ロールのバッチで削除 |

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

スキーマの現在の形は本書 3 章に示す。個々のマイグレーションファイルは変更の単位であり、本書では一覧にしない。

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

`OneToMany` をマッピングしない方針は、N+1 問題と、
意図しない遅延ロードの発生を構造的に避けるため。
必要な形のデータは、その都度クエリで明示的に取得する。

参照系で DTO 射影を使うのは、**ダッシュボードとトレンドが必要とするのは
エンティティ全体ではない**ためである。エンティティを返すと不要な列まで読み、
遅延ロードの誘発点になる。
