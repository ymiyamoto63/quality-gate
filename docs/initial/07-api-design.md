# quality-gate API 設計

| 項目 | 内容 |
| --- | --- |
| ドキュメント名 | quality-gate API 設計（基本設計） |
| バージョン | 1.0 |
| 最終更新 | 2026-09-23 |
| 前提文書 | [要件定義書 v1.1](01-requirements.md) / [方式設計](05-architecture.md) / [DB 設計](06-database-design.md) |

本書で定義した API から `api/openapi.yml` が生成され、
それを入力にフロントエンドの型と呼び出しコードが生成される（[04](04-tech-stack.md) 4 章）。
**本書と実装がずれた場合、正は実装（springdoc の出力）**である。
本書は設計意図と全体像を示すものと位置づける。

---

## 1. 共通仕様

### 1.1 基本方針

| 項目 | 仕様 |
| --- | --- |
| ベースパス | `/api/v1` |
| 形式 | JSON（`application/json; charset=utf-8`）。成果物アップロードのみ `multipart/form-data` |
| 日時 | RFC 3339 / ISO 8601、**UTC 固定**（`2026-09-21T02:10:00Z`）。表示時のタイムゾーン変換はフロントエンドの責務 |
| ID | UUID（文字列） |
| 命名 | パスは複数形のケバブケース、プロパティは `camelCase` |
| 数値 | 割合・時間などの判定対象値は**文字列ではなく数値**で返す。丸めはサーバ側で行い、表示桁数もサーバが決める |
| バージョニング | パスに `v1` を含む。破壊的変更は `v2` を追加する（[02](02-metrics-spec.md) M-09） |

### 1.2 認証

2 つの独立した認証経路を持つ。

| 経路 | 対象 | 方式 |
| --- | --- | --- |
| セッション | `/api/v1/**`（Ingest を除く） | GitHub OAuth ログイン後の `SESSION` Cookie（HttpOnly / SameSite=Lax / Secure） |
| Ingest Token | `/api/v1/runs` 配下の POST（`/reevaluate` を除く）と `GET /api/v1/runs/{runId}/status` | `Authorization: Bearer qg_<prefix>_<secret>` |

同一オリジン構成のため CORS 設定は行わない。
**状態変更を伴う操作には CSRF トークンを要求する**（Spring Security の既定）。
Cookie 認証で CSRF 対策を省くと、外部サイトから利用者の権限で
免除登録や設定変更が実行できてしまう。

Ingest API は Cookie を用いないため CSRF の対象外とし、当該パスのみ除外する。

### 1.3 エラー応答（RFC 9457）

```json
{
  "type": "https://quality-gate.example/problems/artifact-format-invalid",
  "title": "成果物の形式が不正です",
  "status": 422,
  "detail": "jacoco-xml として解釈できませんでした: 行 12 で予期しない要素 <foo> が現れました",
  "instance": "/api/v1/runs/018f8c.../artifacts",
  "errorCode": "ARTIFACT_FORMAT_INVALID",
  "timestamp": "2026-09-21T02:10:05Z",
  "traceId": "3f8a1c...",
  "violations": [
    { "field": "commitSha", "message": "40 桁の 16 進数で指定してください" }
  ]
}
```

- `errorCode` が機械可読な識別子。**クライアントは `title` / `detail` に依存しない**
- `violations` は入力検証エラーのときのみ含む
- `traceId` はサーバログの相関 ID と一致する

### 1.4 ページング

カーソル方式を採る。Run は時系列に増え続け、オフセット方式では
ページ送りの途中で新しい Run が入ると重複・欠落が起きるためである。

```
GET /api/v1/runs?repositoryId=...&limit=20&cursor=eyJtIjoiMjAy...
```

```json
{
  "items": [ ... ],
  "nextCursor": "eyJtIjoiMjAy...",
  "hasMore": true
}
```

カーソルは `(measured_at, id)` を Base64 で符号化したもの。
`limit` の既定は 20、上限は 100。

### 1.5 HTTP ステータスの使い分け

| コード | 用途 |
| --- | --- |
| 200 | 取得・更新の成功 |
| 201 | 生成の成功（`Location` ヘッダを付す） |
| 202 | 受理したが処理は非同期（`finalize`、再評価） |
| 204 | 削除・失効の成功 |
| 400 | リクエスト形式の誤り |
| 401 | 未認証 |
| 403 | 権限不足、許可リスト未登録 |
| 404 | 対象が存在しない、または閲覧権限がない |
| 409 | 状態の競合（finalize 済みの Run への追加など） |
| 413 | ファイルサイズ超過 |
| 422 | 形式は正しいが内容が不正（成果物のパース失敗、設定の検証エラー） |
| 429 | レート制限超過 |
| 5xx | サーバ側の問題 |

---

## 2. エンドポイント一覧

凡例: 認可の `—` は認証のみで可（`VIEWER` 以上）。

### 2.1 Ingest API（収集ランナー / CI → quality-gate）

| メソッド | パス | 用途 | 認可 |
| --- | --- | --- | --- |
| POST | `/api/v1/runs` | Run を作成し `runId` を払い出す | Ingest Token |
| POST | `/api/v1/runs/{runId}/artifacts` | 成果物をアップロード | Ingest Token |
| POST | `/api/v1/runs/{runId}/finalize` | 取り込み完了を宣言 | Ingest Token |
| GET | `/api/v1/runs/{runId}/status` | 処理状態と判定結果（CI のポーリング用の軽量版） | Ingest Token |

### 2.2 参照 API

| メソッド | パス | 用途 | 認可 |
| --- | --- | --- | --- |
| GET | `/api/v1/me` | ログイン中の利用者情報とロール | — |
| GET | `/api/v1/dashboard` | 全リポジトリのサマリ（S-01） | — |
| GET | `/api/v1/repositories` | リポジトリ一覧 | — |
| GET | `/api/v1/repositories/{id}` | リポジトリ詳細（S-02） | — |
| GET | `/api/v1/repositories/{id}/trends` | 指標の時系列（S-05） | — |
| GET | `/api/v1/reports` | 品質レポート（S-10。FR-08-4）。`from` / `to`（日付）と `repositoryId`（複数可）で絞る | — |
| GET | `/api/v1/reports/measurements.csv` | 品質レポートの明細（CSV） | — |
| GET | `/api/v1/runs` | Run 一覧 | — |
| GET | `/api/v1/runs/{runId}` | Run 詳細（S-03） | — |
| GET | `/api/v1/runs/{runId}/findings` | Finding 一覧（S-04） | — |
| GET | `/api/v1/runs/{runId}/artifacts` | 成果物の一覧 | — |
| GET | `/api/v1/runs/{runId}/artifacts/{artifactId}/content` | 成果物のダウンロード | — |
| GET | `/api/v1/repositories/{id}/config` | 現在の設定と版履歴（S-06） | — |
| GET | `/api/v1/waivers` | 免除の一覧（S-07） | — |
| GET | `/api/v1/repositories/{id}/notification-settings` | 通知設定 | — |

### 2.3 操作 API

| メソッド | パス | 用途 | 認可 |
| --- | --- | --- | --- |
| POST | `/api/v1/repositories` | リポジトリ登録 | ADMIN |
| PATCH | `/api/v1/repositories/{id}` | リポジトリ設定の更新 | ADMIN |
| POST | `/api/v1/repositories/{id}/components` | コンポーネント定義 | ADMIN |
| POST | `/api/v1/repositories/{id}/ingest-tokens` | トークン発行（平文は応答時のみ） | ADMIN |
| DELETE | `/api/v1/ingest-tokens/{id}` | トークン失効 | ADMIN |
| POST | `/api/v1/runs/{runId}/reevaluate` | 再評価の実行 | ADMIN |
| POST | `/api/v1/waivers` | 免除の登録 | ADMIN |
| DELETE | `/api/v1/waivers/{id}` | 免除の失効 | ADMIN |
| GET | `/api/v1/users` | 利用者（許可リスト）一覧 | ADMIN |
| POST | `/api/v1/users` | 許可リストへの追加 | ADMIN |
| PATCH | `/api/v1/users/{id}` | ロール変更・無効化 | ADMIN |
| GET | `/api/v1/audit-logs` | 監査ログ | ADMIN |
| GET | `/api/v1/repositories/{id}/ingest-tokens` | トークン一覧（平文もハッシュも返さない） | ADMIN |
| PUT | `/api/v1/repositories/{id}/notification-settings` | 通知条件とメール宛先の更新 | ADMIN |
| GET / PUT | `/api/v1/settings/retention` | 保持期間の取得・更新 | ADMIN |
| GET | `/api/v1/jobs/dead` | 恒久的に失敗したジョブの一覧 | ADMIN |
| POST | `/api/v1/jobs/{id}/retry` | 失敗したジョブの再実行 | ADMIN |

### 2.4 認証以外の公開エンドポイント

| メソッド | パス | 用途 | 認可 |
| --- | --- | --- | --- |
| GET | `/badges/{owner}/{name}.svg` | 既定ブランチの最新判定のバッジ（FR-08-5） | 認証不要 |
| GET | `/actuator/health` | ヘルスチェック | 認証不要 |
| GET | `/actuator/prometheus` | メトリクス | ADMIN（セッション） |

バッジを認証不要にするのは、README に埋め込んだ画像を
ブラウザが Cookie なしで取得するためである。
バッジが返すのは**合否とリポジトリ名のみ**で、指標値や違反内容は含めない。
対象は既定ブランチの最新の判定（`passing` / `passing with warnings` / `failing`）。未登録・無効化・未判定のリポジトリは
どれも `unknown` を返し、どのリポジトリが登録されているかを外から見分けられないようにする。
画像は `Cache-Control: max-age=300` で返す（GitHub の画像プロキシが古いバッジを出し続けないように）。

---

## 3. Ingest API の詳細

### 3.1 `POST /api/v1/runs`

**リクエスト**

```json
{
  "repository": "ymiyamoto63/quality-gate",
  "commitSha": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
  "baseCommitSha": "9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c",
  "branch": "feature/order-api",
  "pullRequestNumber": 1234,
  "triggeredBy": "collector",
  "ciRunUrl": "https://github.com/ymiyamoto63/quality-gate/actions/runs/123456",
  "measuredAt": "2026-09-21T02:10:00Z",
  "skippedMetrics": [
    { "metricId": "M-02", "reason": "PR の計測では PIT を実行しない" },
    { "metricId": "M-03", "reason": "PR の計測では k6 を実行しない" }
  ]
}
```

| 項目 | 必須 | 備考 |
| --- | --- | --- |
| `repository` | ○ | `owner/name`。トークンの発行元と一致しない場合 403 |
| `commitSha` | ○ | 40 桁の 16 進 |
| `baseCommitSha` | | 省略時は quality-gate が判定ジョブの中で GitHub API により求めて記録する（PR はマージ先との merge-base、既定ブランチは直前のコミット、それ以外は既定ブランチとの merge-base）。求められなければ未指定のまま判定する（`QG_GITHUB_*`、[設定値](../operations/configuration.md)） |
| `branch` | ○ | |
| `pullRequestNumber` | | |
| `measuredAt` | ○ | |
| `skippedMetrics` | | 省略時は「全指標を計測した」とみなす |

**応答（201）**

```json
{
  "runId": "018f8c1a-...",
  "attempt": 1,
  "status": "CREATED",
  "detailUrl": "https://quality-gate.example/runs/018f8c1a-..."
}
```

`detailUrl` を返すのは、CI のログに Run 詳細への直リンクを出せるようにするため。
不合格を知ったときに、その場から詳細へ飛べる。

### 3.2 `POST /api/v1/runs/{runId}/artifacts`

`multipart/form-data`。

| パート | 内容 |
| --- | --- |
| `file` | 成果物ファイル |
| `type` | `jacoco-xml` など（[02](02-metrics-spec.md) 0.5） |
| `component` | `backend` / `frontend`（任意） |
| `scope` | `base` / `head`（M-07 のベース比較用、任意） |
| `metadata` | JSON オブジェクトの文字列。性能成果物では `environment`、PIT（`pit-xml`）では `mutationScope`（`changed` / `all`）が必須。oasdiff（`oasdiff-json`）では比較元に OpenAPI 定義が無かったことを `baseSpecMissing: true` で申告できる |

**応答（202）**

```json
{ "artifactId": "018f8c1b-...", "sizeBytes": 245678, "sha256": "e3b0c442..." }
```

| エラー | コード | 条件 |
| --- | --- | --- |
| 409 | `RUN_ALREADY_FINALIZED` | finalize 済みの Run |
| 413 | `ARTIFACT_TOO_LARGE` | 50MB 超、または Run 合計 200MB 超 |
| 422 | `ARTIFACT_TYPE_UNKNOWN` | 未知の `type` |
| 422 | `PERFORMANCE_METADATA_MISSING` | 性能成果物で `environment` が欠落 |
| 422 | `MUTATION_SCOPE_MISSING` | PIT の成果物で `mutationScope` が欠落 |
| 400 | `VALIDATION_FAILED` | `metadata` が JSON オブジェクトでない、`mutationScope` が選択肢に無い、`baseSpecMissing` が真偽値でない |

**この時点ではパースしない。** 受領・検証・保存のみを行い、
パースは判定ジョブで実施する。アップロードごとにパースすると、
CI の待ち時間がファイル数に比例して延びるためである。

### 3.3 `POST /api/v1/runs/{runId}/finalize`

リクエストボディなし。

**応答（202）**

```json
{ "runId": "018f8c1a-...", "status": "FINALIZED", "detailUrl": "https://..." }
```

判定の完了は待たない（[05](05-architecture.md) 3.1）。

### 3.4 `GET /api/v1/runs/{runId}/status`

CI からのポーリング用。Run 詳細より軽量な応答を返す。

```json
{
  "runId": "018f8c1a-...",
  "status": "EVALUATED",
  "verdict": "FAIL",
  "completeness": "PARTIAL",
  "summary": {
    "pass": 5, "warn": 1, "fail": 1, "skip": 4, "reference": 0, "error": 0
  },
  "failedMetrics": [
    { "metricId": "M-06", "name": "重大・高 脆弱性件数", "value": 2, "threshold": 0 }
  ],
  "detailUrl": "https://..."
}
```

---

## 4. 参照 API の詳細

### 4.1 `GET /api/v1/dashboard`

`repository_summaries`（[06](06-database-design.md) 3.14）を読むだけで応答する。

```json
{
  "repositories": [
    {
      "repositoryId": "018f...",
      "fullName": "ymiyamoto63/quality-gate",
      "latestRun": {
        "runId": "018f8c1a-...",
        "verdict": "FAIL",
        "completeness": "PARTIAL",
        "measuredAt": "2026-09-21T02:12:30Z",
        "branch": "main"
      },
      "categories": [
        { "category": "機能テスト",   "status": "PASS" },
        { "category": "性能テスト",   "status": "SKIP" },
        { "category": "セキュリティ", "status": "FAIL" },
        { "category": "コード構造",   "status": "PASS" },
        { "category": "契約・互換性", "status": "PASS" },
        { "category": "使いやすさ",   "status": "WARN" }
      ],
      "openCriticalCount": 1,
      "openHighCount": 1,
      "activeWaiverCount": 2,
      "freshness": {
        "lastMeasuredAt": "2026-09-21T02:12:30Z",
        "lastFullMeasuredAt": "2026-09-14T02:11:00Z",
        "staleMeasurement": false,
        "staleFullMeasurement": true
      }
    }
  ],
  "alerts": [
    { "code": "FULL_MEASUREMENT_STALE", "repositoryId": "018f...", "days": 7 }
  ]
}
```

`freshness` を応答に含めるのは、FR-06-2（計測途絶）と FR-06-3（完全計測途絶）を
**画面側で計算させない**ため。基準日数は設定値であり、
サーバが判定してブール値で返すほうが、設定変更が画面に確実に反映される。

### 4.2 `GET /api/v1/runs/{runId}`

```json
{
  "runId": "018f8c1a-...",
  "repository": { "repositoryId": "018f...", "fullName": "ymiyamoto63/quality-gate" },
  "commitSha": "a1b2c3d4...",
  "commitUrl": "https://github.com/ymiyamoto63/quality-gate/commit/a1b2c3d4...",
  "baseCommitSha": "9f8e7d6c...",
  "baselineRunId": "018f8b02-...",
  "branch": "main",
  "pullRequestNumber": null,
  "status": "EVALUATED",
  "verdict": "FAIL",
  "completeness": "PARTIAL",
  "measuredAt": "2026-09-21T02:10:00Z",
  "evaluatedAt": "2026-09-21T02:12:30Z",
  "ciRunUrl": "https://github.com/.../actions/runs/123456",
  "gateConfig": { "gateConfigId": "018f...", "version": 3, "sourceType": "FILE" },
  "categories": [
    {
      "category": "機能テスト",
      "status": "PASS",
      "metrics": [
        {
          "metricId": "M-01",
          "name": "ブランチカバレッジ",
          "component": "backend",
          "status": "PASS",
          "value": 82.4,
          "unit": "percent",
          "threshold": { "operator": ">=", "value": 75 },
          "previousValue": 81.9,
          "delta": 0.5,
          "reason": "しきい値 75% を満たしています",
          "findingCount": 0
        },
        {
          "metricId": "M-02",
          "name": "ミューテーションスコア",
          "component": "backend",
          "status": "SKIP",
          "value": null,
          "skipReason": "PR の計測では PIT を実行しない",
          "skipAccepted": true
        }
      ]
    }
  ],
  "findingSummary": {
    "new": 2, "continuing": 5, "resolved": 3, "waived": 2
  },
  "skippedMetrics": [
    { "metricId": "M-02", "reason": "...", "accepted": true }
  ]
}
```

指標をカテゴリでまとめて返すのは、Run 詳細画面が
**6 カテゴリの表として描画される**ため（[08](08-screen-design.md) 4.3）。
平坦な配列を返して画面側で分類すると、カテゴリの定義が
サーバとクライアントの 2 箇所に存在することになる。

`reason` を**日本語の文として**返すのも同じ理由で、
「しきい値をどう解釈したか」の表現をサーバに一元化する。

### 4.3 `GET /api/v1/runs/{runId}/findings`

| クエリ | 値 |
| --- | --- |
| `metricId` | `M-06` など |
| `state` | `NEW` / `CONTINUING` / `RESOLVED` / `INITIAL`（複数可） |
| `severity` | `CRITICAL` / `HIGH` / ...（複数可） |
| `waived` | `true` / `false` |
| `limit` / `cursor` | ページング |

```json
{
  "items": [
    {
      "findingId": "018f...",
      "metricId": "M-06",
      "state": "NEW",
      "severity": "HIGH",
      "title": "CVE-2026-1234: example-lib の任意コード実行",
      "filePath": "backend/pom.xml",
      "line": null,
      "sourceUrl": "https://github.com/.../blob/a1b2c3d4/backend/pom.xml",
      "detail": {
        "package": "com.example:example-lib",
        "installedVersion": "1.2.3",
        "fixedVersion": "1.2.5",
        "cvssScore": 8.1,
        "advisoryUrl": "https://..."
      },
      "waiver": null
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

`sourceUrl` はサーバが組み立てて返す。GitHub の URL 形式を
フロントエンドに持たせると、ホスティング先が変わったときに
両方を直す必要が生じる。

### 4.4 `GET /api/v1/repositories/{id}/trends`

| クエリ | 値 |
| --- | --- |
| `metricId` | 必須。複数指定可 |
| `component` | 任意 |
| `from` / `to` | 既定は直近 30 日 |
| `branch` | 既定は `main` |

```json
{
  "metricId": "M-03",
  "unit": "ms",
  "threshold": { "operator": "<=", "value": 500 },
  "series": [
    {
      "seriesId": "all/perf-staging",
      "label": "perf-staging",
      "judged": true,
      "points": [
        { "runId": "018f...", "measuredAt": "2026-09-14T02:11:00Z", "value": 412.5, "status": "PASS" },
        { "runId": "018f...", "measuredAt": "2026-09-16T02:11:00Z", "value": null,  "status": "SKIP" },
        { "runId": "018f...", "measuredAt": "2026-09-18T02:11:00Z", "value": 468.1, "status": "WARN" }
      ]
    }
  ]
}
```

- **計測環境ごとに系列を分ける**（FR-08-2）。系列の分割はサーバが行う
- `judged: false` の系列（全点が参考値）は、画面では破線で描く（FR-08-6）
- スキップした点は `value: null` として返す。**0 を返さない**。
  0 を返すと、グラフ上で「性能が極めて良い」ように見えてしまう

#### 実装での確定事項

**系列を分ける軸は「同じ条件で比較できるか」である。**

| 軸 | 適用 |
| --- | --- |
| コンポーネント | 常に分ける。backend と frontend のカバレッジを 1 本の線にしても意味がない |
| 計測条件（`variant`） | 値に計測条件が添えられている場合に分ける。M-02 の実行範囲（変更範囲 / 全量）と、性能の計測環境（`environment.name`）。範囲や環境が切り替わるたびに品質が乱高下して見えるのを防ぐ |

ランナー種別（self-hosted / github-hosted）の軸は、計測を収集ランナーに絞ったため削除した（D-19）。
系列名は「backend（変更範囲）」のように条件を括弧内に並べる。

**計測条件を持たない点は、同じコンポーネントの条件つき系列に配る。** 成果物の未提出などに
よる計測エラーは実行範囲が分からない。これを別系列にすると、下のコンポーネントを持たない点と
同じく欠測が 1 本の線として現れる。

**対象外（`NOT_APPLICABLE`）の点は返さない。** 測りようのないものは欠測ですらなく、
系列を作ると値の無い線が 1 本増えるだけになる。

**コンポーネントを持たない点は、各コンポーネントの系列に配る。**
スキップと計測エラーは指標ごと Run ごとに起きるため、判定結果にコンポーネント名が
付かない。これをそのまま別系列にすると、<strong>欠測が「もう 1 本の線」として
現れる</strong>。未計測は線を途切れさせるものであって、新しい系列ではない。

**色は `colorIndex` としてサーバが固定して返す。** 画面側で「並び順の n 番目」に
色を振ると、絞り込みで系列が減ったときに生き残った系列の色が塗り替わる。

**しきい値は期間内で一定とは限らない。** 設定で変えられるため、過去の点は当時の
基準で判定されている。応答は期間内で最後に適用された値を `threshold` として返し、
変化があったことを `thresholdChanged` で示す。1 本の線だけでは過去の判定を
説明できないことを、画面が利用者に伝えられるようにするためである。

**判定できていない Run は含めない。** 処理に失敗した Run を含めると、
品質の変化ではなく計測基盤の不調がグラフに現れる。

### 4.5 `GET /api/v1/repositories/{id}/config`

```json
{
  "current": {
    "gateConfigId": "018f...",
    "version": 3,
    "sourceType": "FILE",
    "sourceCommitSha": null,
    "createdAt": "2026-09-20T01:00:00Z",
    "rawYaml": "version: 1\n...",
    "parsed": { "metrics": { "branch_coverage": { "threshold": 75 } } }
  },
  "history": [
    { "gateConfigId": "018f...", "version": 3, "sourceType": "FILE", "createdAt": "..." },
    { "gateConfigId": "018e...", "version": 2, "sourceType": "FILE", "createdAt": "..." }
  ],
  "validation": { "valid": true, "errors": [] }
}
```

検証エラーがある場合:

```json
{
  "validation": {
    "valid": false,
    "errors": [
      { "line": 14, "path": "metrics.branch_coverage.threshold", "message": "0〜100 の数値を指定してください（受信値: \"75%\"）" },
      { "line": 22, "path": "metrics.mutation_score.targets", "message": "未知のキーです。'components' の誤りではありませんか" }
    ]
  }
}
```

行番号と、typo の候補提示まで返す。設定ファイルの誤りは
**書いた人が自力で直せる情報**とセットで返さないと、問い合わせに変わる。

---

## 4.6 実装で確定した仕様

本書と実装がずれた場合の正は実装（springdoc の出力）である（冒頭の前提）。
参照 API の実装にあたって確定した点を記す。

### 応答スキーマの命名

入れ子の型には**応答をまたいで一意な名前**を付ける（`RunSummary` / `FindingItem` など）。
springdoc はスキーマ名に Java の単純名を使うため、別の応答に同じ名前の入れ子型があると
片方の定義がもう片方を静かに上書きし、生成されるクライアント型が別物になる
（[04](04-tech-stack.md) 10.6）。

### 必須と null の宣言

- 常に返す項目は `required` に含める
- null を返しうる項目は `nullable: true` を付ける

この 2 つを宣言して初めて、**「常に存在し、値は null でありうる」**（未計測の値など）と
**「そもそも項目が無い」**を区別できる。本 API は「null は未計測 / 0 は計測して 0」を
厳密に分けているため、この区別が仕様に出ていなければクライアントに伝わらない。

### 合格ラインの形

`threshold` は指標によらず `{ "operator": ..., "value": ... }` を必ず含む。
画面はこの 2 つだけで「≥ 75%」「≤ 0 件」と描ける。内訳（`maxCritical` / `maxHigh` など）は
追加のキーとして添える。指標ごとに形が違うと、画面に指標ごとの分岐が生まれる。

### カーソルの中身

カーソルは不透明な文字列として扱い、クライアントは中身を解釈しない。
実装では対象によって方式を変えている。

| 対象 | 方式 | 理由 |
| --- | --- | --- |
| Run 一覧 | `(measuredAt, id)` のキーセット | 時系列に増え続けるため、オフセットでは送り中に重複・欠落が起きる |
| 違反一覧 | 位置（オフセット） | Run に属する違反は判定時に確定したスナップショットで、送り中に増減しない |

不透明にしてあるため、必要になれば違反一覧もキーセットへ移せる。
種類の違うカーソルを渡した場合は 400（`VALIDATION_FAILED`）を返す。

### 絞り込みの未知の値

`state` / `severity` に未知の値を渡した場合は 400 を返し、黙って無視しない。
無視すると、綴りを間違えたフィルタが「絞り込まない」として通り、
利用者には「絞り込んだのに全件出た」ように見える。

### 計測条件（`variant` / `variantLabel`）

Run 詳細の指標行は、計測条件の生の値（`variant`: `changed` / `all`）と表示名
（`variantLabel`: `変更範囲` / `全量`）の両方を返す。条件の区別が無い指標ではどちらも `null`。
表示名をサーバが持つのは、トレンドの系列名と同じ語を使うためである。画面側に対応表を持つと、
条件を足したときに片方だけ生の値を出す。

`previousValue` / `delta` は条件の一致する比較対象からだけ算出する。
条件の違う値との差は、改善や悪化ではなく条件の違いを表すだけだからである。

### 判定ステータス `NOT_APPLICABLE`

`status` に `NOT_APPLICABLE`（対象外）が加わった。ツールの制約でそのコンポーネントでは
測りようがないことを表す（M-02 の frontend。[02](02-metrics-spec.md) M-02）。
`SKIP`（今回は測らなかった）とは別物であり、画面も「未計測」ではなく「対象外」と表示する。
`value` は常に `null`。合否・部分計測・カテゴリの状態のいずれにも影響しない。

### アクセシビリティ（M-10）の指標行と違反

M-10 の `threshold` は他の指標と同じ `operator` / `value`（critical + serious の許容件数）に加え、
判定基準の `standard`（`wcag22aa` など）を持つ。`detail` は基準内の `critical` / `serious` /
`moderate` / `minor` の件数、基準外の `outOfStandard`、判断を保留した要素数 `needsReview`、
検査した画面 `pages`、読み込みに失敗した画面 `failedPages` を返す。

M-10 の違反は `filePath` / `line` / `sourceUrl` が `null` である。リポジトリ上のファイルではなく
画面の要素を指すため、GitHub へのリンクを組み立てると壊れたリンクになる。位置は `detail` の
`page`（`/runs/:id`）と `selector`（CSS セレクタ）で示し、`impact`・`tags`・`helpUrl`・
`html`（先頭 512 文字）・`failureSummary` を添える。

### 契約・互換性（M-08 / M-09）の指標行と違反

M-08 の `threshold` は `operator`（`>=`）/ `value`（成功率 %）に加え、最小実行件数 `minTestCount` を持つ。
`detail` は実行件数 `executed` と結果別の `passed` / `failed` / `errored` / `skipped` / `flaky`
（再実行で成功）を返し、コンポーネントを宣言した成果物があれば `components` にコンポーネント別の同じ内訳を返す。
`value` は合算した成功率で、切り捨てで丸める（失敗があるのに `100` と表示しない）。

M-08 の違反は失敗・エラー・スキップ・再実行で成功したテスト 1 件ごとに 1 件で、`ruleId` が
`failed` / `errored` / `skipped` / `flaky` になる。`detail` に `testClass`・`testName`・`outcome`、
あれば `failureType` と `message`（先頭 512 文字）を添える。

M-09 の `detail` は破壊的変更 `breaking`（oasdiff の level 3）、破壊的になりうる変更 `warnings`（level 2）、
非破壊的な変更 `informational`（level 1）の件数を返す。違反になるのは level 3 と 2 だけで、
`ruleId` は oasdiff の変更 ID（`api-path-removed-without-deprecation` など）、`detail` に
`level`（`error` / `warning`）・`operation`・`apiPath`・`operationId`・`section`・`source` を添える。
比較元に OpenAPI 定義が無い Run では `status` が `NOT_APPLICABLE` になる。

M-08 / M-09 の違反も `filePath` / `line` / `sourceUrl` が `null` である。テストクラス名や
API のパスはリポジトリ上のファイルではない。

### 操作 API で確定した仕様

本書 2.3 の操作 API はすべて実装した。設計から変わった点・足した点を記す。

| 項目 | 実装 |
| --- | --- |
| 権限不足 | メソッドセキュリティの拒否は `403 FORBIDDEN`（未処理の例外として 500 にしない） |
| CSRF | Cookie 方式（`XSRF-TOKEN` Cookie を `X-XSRF-TOKEN` ヘッダで送り返す）。SPA の API クライアントが自動で付ける |
| ロールの反映 | セッションのロールを信じず、リクエストのたびに `users` の現在値で置き換える。降格・無効化は次のリクエストから効く |
| 利用者 | `PATCH /api/v1/users/{id}` で自分自身の降格・無効化、有効な管理者が 0 人になる変更は `409 ADMIN_REQUIRED`。同名の登録は `409 USER_ALREADY_EXISTS` |
| リポジトリ | 大文字小文字を問わず同じ `owner/name` は `409 REPOSITORY_ALREADY_EXISTS`。無効化したリポジトリへの Run 作成は `403 FORBIDDEN` |
| トークン | 一覧は平文もハッシュも返さない。監査ログにも平文を残さない |
| 設定 | `GET .../config` は表示だけ（更新の API は無い。設定は `collector/targets/*.gate.yml` を Git で管理する。D-20）。`defaultYaml` を返す。直近の Run が設定の検証エラーで失敗していれば、その設定ファイルを検証し直して行番号つきのエラーと内容（`validation.rawYaml`）を返す |
| 免除 | 違反の免除（`scope: FINDING`）は、そのリポジトリで検出されたことのある違反だけを対象にできる（無ければ 404）。登録時点の違反の見出しを `title` に複製する。指標の免除（`scope: METRIC`）は判定を `REFERENCE` にして本来の判定を理由に残し、ダッシュボードの `alerts` に `METRIC_WAIVED` を常に出す。違反の免除は違反を数える指標（M-06 / M-07 / M-09 / M-10）に効く。M-08 は件数の集計で判定するため、指標の免除を使う |
| 違反一覧 | 各違反に `fingerprint`、応答に `repositoryId` を返す（免除の登録に使う）。`waiver.status` は判定後に失効・期限切れになっていれば `ACTIVE` 以外 |
| 再評価 | `POST /api/v1/runs/{id}/reevaluate` の `status` は受付時点の Run の状態。取り込みが確定していない Run は `409 RUN_NOT_EVALUABLE` |
| ジョブ | 恒久的失敗（`DEAD`）の確認と手動再実行（[05](05-architecture.md) 4.3） |
| 保持期間 | Run・成果物・監査ログ・通知の日数を扱う |
| 通知設定 | 通知条件とメールの宛先（D-15 でメールのみ） |
| 成果物 | `GET /api/v1/runs/{id}/artifacts` と `.../content`。実体が削除済みなら `409 ARTIFACTS_DELETED`。必ずダウンロードとして返す（`Content-Disposition: attachment`） |

---

## 5. 操作 API の詳細

### 5.1 `POST /api/v1/waivers`

```json
{
  "repositoryId": "018f...",
  "scope": "FINDING",
  "metricId": "M-06",
  "fingerprint": "e3b0c44298fc1c14...",
  "reasonCategory": "NO_FIX_AVAILABLE",
  "reason": "上流に修正版が未提供。リバースプロキシ側で該当パスを遮断済み（PR #456）",
  "expiresAt": "2026-10-21T00:00:00Z"
}
```

| 検証 | 内容 |
| --- | --- |
| `reason` | 必須。20 文字以上（「対応済み」のような実質のない理由を防ぐ） |
| `expiresAt` | 必須。現在時刻より後、かつ最大 90 日先まで |
| 重複 | 同一 `(repositoryId, metricId, fingerprint)` に有効な免除が既にある場合 409 |

**応答（201）** で登録された免除を返す。同時に:

1. 監査ログに `WAIVER_CREATED` を記録
2. 当該リポジトリの最新 Run の再評価ジョブを登録（免除の効果を即座に反映する）

2 を自動で行うのは、免除を登録したのにダッシュボードが
不合格のままだと、登録が効いたのか分からないためである。

### 5.2 `POST /api/v1/repositories/{id}/ingest-tokens`

**応答（201）**

```json
{
  "tokenId": "018f...",
  "token": "qg_a1b2c3d4_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
  "tokenPrefix": "a1b2c3d4",
  "createdAt": "2026-09-21T03:00:00Z"
}
```

`token` は**この応答でのみ**返す。以後どの API からも取得できない。
画面には「この値は二度と表示されません」と明示し、
コピーするまで閉じられないダイアログで表示する（[08](08-screen-design.md) 4.8）。

### 5.3 `POST /api/v1/runs/{runId}/reevaluate`

**応答（202）**

```json
{ "runId": "018f...", "status": "PROCESSING", "jobId": "018f..." }
```

成果物が保持期間を過ぎて削除されている場合は 409 `ARTIFACTS_DELETED` を返す。
再評価は保存済みの成果物を読み直すため、実体が無いと実行できない。

### 5.4 `POST /api/v1/users`

```json
{ "githubLogin": "someone", "role": "VIEWER" }
```

`githubLogin` の実在確認は行わない。GitHub API を呼ぶと、
存在しないユーザーを登録しようとした時点で外部依存の障害に巻き込まれる。
実在しない名前を登録しても、そのユーザーはログインできないだけで害がない。

---

## 6. 認可マトリクス

| 操作 | VIEWER | ADMIN | Ingest Token |
| --- | :---: | :---: | :---: |
| ダッシュボード・Run・Finding・トレンドの閲覧 | ○ | ○ | — |
| 設定の閲覧 | ○ | ○ | — |
| 免除の閲覧 | ○ | ○ | — |
| 成果物のダウンロード | ○ | ○ | — |
| Run の作成・成果物の送信・finalize | — | — | ○ |
| 再評価の実行 | — | ○ | — |
| 免除の登録・失効 | — | ○ | — |
| リポジトリ登録・設定変更 | — | ○ | — |
| トークンの発行・失効 | — | ○ | — |
| 利用者・ロールの管理 | — | ○ | — |
| 監査ログの閲覧 | — | ○ | — |

Ingest Token は**書き込み専用**であり、参照 API を一切呼べない。
CI に置かれる認証情報であるため、漏洩時の影響を
「偽の計測結果を送れる」に限定し、蓄積データの読み出しには使えないようにする。

---

## 7. エラーコード一覧

| `errorCode` | HTTP | 意味 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | リクエストの検証エラー（`violations` に詳細）。パスやクエリの値の形式の誤り、必須パラメータの欠落も含む |
| `UNAUTHENTICATED` | 401 | 未認証 |
| `TOKEN_INVALID` | 401 | Ingest Token が不正または失効済み |
| `USER_NOT_ALLOWLISTED` | 403 | 認証は成功したが許可リストに未登録 |
| `USER_DISABLED` | 403 | アカウントが無効 |
| `FORBIDDEN` | 403 | 権限不足 |
| `REPOSITORY_MISMATCH` | 403 | トークンの発行元と `repository` が不一致 |
| `RESOURCE_NOT_FOUND` | 404 | 対象が存在しない（存在しない API の URL も含む） |
| `METHOD_NOT_ALLOWED` | 405 | その URL で使えない HTTP メソッド。`Allow` ヘッダに使えるメソッドを付ける |
| `NOT_ACCEPTABLE` | 406 | `Accept` で求められた形式では応答できない（API は JSON だけを返す） |
| `RUN_ALREADY_FINALIZED` | 409 | finalize 済みの Run への操作 |
| `USER_ALREADY_EXISTS` | 409 | 同じ GitHub ログイン名の利用者が既にある |
| `ADMIN_REQUIRED` | 409 | 自分自身の降格・無効化、または有効な管理者が 0 人になる変更 |
| `REPOSITORY_ALREADY_EXISTS` | 409 | 同じリポジトリが既に登録されている |
| `RUN_NOT_EVALUABLE` | 409 | 取り込みが確定していない Run の再評価 |
| `WAIVER_ALREADY_EXISTS` | 409 | 同一対象に有効な免除が存在する |
| `ARTIFACTS_DELETED` | 409 | 成果物が保持期間経過で削除済み（再評価不可） |
| `ARTIFACT_TOO_LARGE` | 413 | ファイルまたは Run 合計のサイズ超過 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 本文の形式（`Content-Type`）に対応していない |
| `ARTIFACT_TYPE_UNKNOWN` | 422 | 未知の成果物種別 |
| `ARTIFACT_FORMAT_INVALID` | 422 | パースに失敗 |
| `PERFORMANCE_METADATA_MISSING` | 422 | 性能成果物の `environment` が欠落 |
| `MUTATION_SCOPE_MISSING` | 422 | PIT の成果物の `mutationScope` が欠落 |
| `CONFIG_VALIDATION_FAILED` | 422 | `.quality-gate.yml` の検証エラー |
| `WAIVER_EXPIRY_TOO_FAR` | 422 | 免除期限が 90 日を超える |
| `RATE_LIMITED` | 429 | レート制限超過（8 章）。`Retry-After` ヘッダに待つ秒数を付ける |
| `GITHUB_UNAVAILABLE` | 502 | GitHub API の障害（定義のみ。GitHub API を呼ぶのは判定ジョブの比較元の解決だけで、失敗しても比較元なしで判定を続けるため、API の応答としては返さない） |
| `INTERNAL_ERROR` | 500 | 想定外の例外。Spring MVC が要求の誤りとして投げる例外（4xx の状態コードを持つもの）はここに含めず、上の該当するコードで返す |

---

## 8. レート制限

内部利用のため厳しい制限は設けないが、**暴走の歯止めとして**設定する。

| 対象 | 制限 |
| --- | --- |
| Ingest API（トークン単位） | 60 リクエスト / 分 |
| 成果物アップロード（トークン単位） | 100 ファイル / 分 |
| 参照 API（セッション単位） | 600 リクエスト / 分 |
| バッジ（IP 単位） | 60 リクエスト / 分 |

超過時は 429 と `Retry-After` ヘッダを返す。
CI の不具合で同じジョブが無限に再実行されるような事故で、
ストレージと DB が食い潰されることを防ぐのが目的である。

**実装で確定した仕様。**

- トークンバケットで数える。容量は 1 分あたりの上限、補充は上限 ÷ 60 秒の速さで連続的に行う
  （固定の 1 分窓のように、窓の境目で上限の 2 倍が通ることがない）
- Ingest API の「成果物アップロード」は `POST /api/v1/runs/{runId}/artifacts`、それ以外の Ingest API
  （Run 作成・確定・状態取得）は「Ingest API」の枠で数える。単位は Ingest Token
- 参照 API はログインした利用者ごとに数える。未ログインの要求（どのみち 401 になる）は IP ごと
- API 以外（画面の静的ファイル、`/actuator/**`）は制限しない
- 枠はプロセスのメモリに持つ（単一ホストでの運用が前提。Q-10）。再起動すると枠は戻る
- 上限は `quality-gate.rate-limit.*`、無効にするなら `QG_RATE_LIMIT_ENABLED=false`。
  超過は WARN ログに残す（`category` / 主体 / パス）
- 収集ランナー（`collector/bin/submit.sh`）は 429 を受けると `Retry-After` に従って 3 回まで再試行する（curl の `--retry`）

---

## 9. OpenAPI 仕様の生成と検証

| 項目 | 方針 |
| --- | --- |
| 生成 | springdoc がコントローラと DTO から生成（[04](04-tech-stack.md) 4.1） |
| 出力先 | `api/openapi.yml`（リポジトリにコミット） |
| 同期検証 | CI で再生成し `git diff --exit-code` が通ることを確認（[04](04-tech-stack.md) 4.3） |
| 互換性検証 | `oasdiff` でベースとの破壊的変更を検出（M-09） |
| 記述の補強 | `@Schema(description = ...)` を DTO に付与。生成された仕様がフロントエンドの唯一の参照先になるため、説明を実装側に書く |

### DTO 設計の原則

| 原則 | 理由 |
| --- | --- |
| エンティティをそのまま返さない | DB のスキーマ変更が API の破壊的変更に直結する |
| `null` と「値が無い」を区別する | `value: null` は「計測していない」、`0` は「計測して 0 だった」。この 2 つは意味が違う |
| 列挙値は文字列で返す | 数値だと値の追加で既存の意味が変わる |
| 表示用の文字列（`reason` / `label`）をサーバが返す | 判定基準の表現を 1 箇所に集約する |

`null` と `0` の区別は本システムの根幹にあたる。
カバレッジ `0` は「テストが 1 行も通っていない」という深刻な状態、
`null` は「計測していない」。取り違えると、未計測が
最悪の値として扱われたり、逆に見過ごされたりする。
