# quality-gate API 設計

**API の正本は実装から生成した `api/openapi.yml` である**（エンドポイント・リクエスト・応答の型）。
本書はその全体像と、仕様から読み取れない設計の意図だけを記す。

---

## 1. 共通仕様

| 項目 | 仕様 |
| --- | --- |
| ベースパス | `/api/v1` |
| 形式 | JSON。成果物のアップロードだけ `multipart/form-data` |
| 日時 | ISO 8601、UTC（`2026-09-21T02:10:00Z`）。表示のタイムゾーン変換は画面が行う |
| 数値 | 判定に使う値は文字列でなく数値で返す |
| `null` と `0` | `value: null` は「計測していない」、`0` は「計測して 0 だった」。常に区別する。常に返す項目は `required`、null を返しうる項目は `nullable` を宣言する |
| 表示用の文字列 | 判定理由（`reason`）や計測条件の表示名（`variantLabel`）はサーバが返す。表現を 1 か所に集める |
| 合格ラインの形 | `threshold` は指標によらず `{ "operator", "value" }` を必ず含む。内訳は追加のキーで添える |
| ページング | カーソル方式（`limit` 既定 20・上限 100、`nextCursor` / `hasMore`）。カーソルは不透明な文字列 |
| エラー | RFC 9457 の Problem Details に `errorCode`（機械可読）・`traceId`・`violations`（入力検証のみ）を足す。クライアントは `title` / `detail` に依存しない |

### 1.1 認証

| 経路 | 対象 | 方式 |
| --- | --- | --- |
| セッション | `/api/v1/**`（Ingest を除く） | GitHub ログイン後の `SESSION` Cookie（HttpOnly / SameSite=Lax / Secure）。状態を変える操作には CSRF トークン（`XSRF-TOKEN` Cookie を `X-XSRF-TOKEN` ヘッダで送り返す）を求める |
| Ingest Token | `POST /api/v1/runs`・`.../artifacts`・`.../finalize` | `Authorization: Bearer <token>`。バックエンドの `QG_INGEST_TOKEN` に設定した値（送り手は収集ランナーだけ。DD-20）。Cookie を使わないため CSRF の対象外 |

同一オリジン構成のため CORS は設定しない。セッションのロールは信じず、リクエストのたびに `users` の現在値で置き換える。

---

## 2. エンドポイント

凡例: 認可の `—` はログインしていれば可（VIEWER 以上）。

| メソッド | パス | 用途 | 認可 |
| --- | --- | --- | --- |
| POST | `/api/v1/runs` | Run を作成する（初めてのリポジトリは登録する） | Ingest Token |
| POST | `/api/v1/runs/{runId}/artifacts` | 成果物をアップロードする | Ingest Token |
| POST | `/api/v1/runs/{runId}/finalize` | 取り込みを確定し、その場で判定する | Ingest Token |
| GET | `/api/v1/me` | ログイン中の利用者とロール | — |
| GET | `/api/v1/dashboard` | 全リポジトリのサマリ（S-01） | — |
| GET | `/api/v1/repositories/{id}` | リポジトリ詳細（S-02） | — |
| GET | `/api/v1/repositories/{id}/trends` | 指標の時系列（S-05） | — |
| GET | `/api/v1/repositories/{id}/config` | 直近の Run に送られた合格ラインと検証結果（S-06） | — |
| GET | `/api/v1/repositories/{id}/release-report` | リリース判定（S-08）。`ref` にタグかコミット SHA | — |
| GET | `/api/v1/repositories/{id}/release-report.csv` | リリース判定の CSV。出力を監査ログに残す | — |
| GET | `/api/v1/runs` | Run 一覧 | — |
| GET | `/api/v1/runs/{runId}` | Run 詳細（S-03） | — |
| GET | `/api/v1/runs/{runId}/findings` | 違反一覧（S-04） | — |
| GET | `/api/v1/runs/{runId}/artifacts`、`.../{artifactId}/content` | 成果物の一覧とダウンロード | — |
| POST | `/api/v1/runs/{runId}/reevaluate` | 再評価 | ADMIN |
| GET / POST / PATCH | `/api/v1/users`、`/api/v1/users/{id}` | 許可リスト・ロールの管理 | ADMIN |
| GET | `/api/v1/audit-logs` | 監査ログ | ADMIN |
| GET | `/actuator/health` | ヘルスチェック | 認証不要 |
| GET | `/actuator/prometheus` | メトリクス | ADMIN |

**Ingest Token は書き込み専用**で、参照 API を呼べない。漏えいしたときの影響を「偽の計測結果を送れる」に限る。

---

## 3. Ingest API

収集ランナー（`collector/bin/submit.sh`）は、Run 作成 → 成果物のアップロード → 確定 の順に呼ぶ。

### 3.1 `POST /api/v1/runs`

| 項目 | 必須 | 備考 |
| --- | --- | --- |
| `repository` | ○ | `owner/name`。初めて送られたリポジトリは登録する |
| `commitSha` | ○ | 40 桁の 16 進 |
| `branch` / `triggeredBy` / `measuredAt` | ○ | |
| `defaultBranch` | | 計測プロファイルの `DEFAULT_BRANCH`。リポジトリの既定ブランチを更新する（省略時は変えない。新規は `main`） |
| `baseCommitSha` | | 比較元（[02](02-metrics-spec.md) 0.3）。収集ランナーが clone した履歴から求める |
| `configCommitSha` | | 合格ラインを送った quality-gate リポジトリのコミット |
| `pullRequestNumber` / `ciRunUrl` | | |
| `tags` | | 計測したコミットを指すタグ（`git tag --points-at`）。リリース判定でタグをコミットに解決するのに使う |
| `skippedMetrics` | | 計測しなかった指標と理由。省略時は全指標を計測したとみなす |

同じコミットへの再送信は `attempt` を増やした新しい Run になる。応答の `detailUrl` は収集ランナーのログに Run 詳細への直リンクを出すため。

### 3.2 `POST /api/v1/runs/{runId}/artifacts`

| パート | 内容 |
| --- | --- |
| `file` | 成果物 |
| `type` | `jacoco-xml` など（[02](02-metrics-spec.md) 0.5）。合格ラインは `quality-gate-config` |
| `component` | `backend` / `frontend`（任意） |
| `metadata` | JSON オブジェクトの文字列。性能（`k6-summary`）は `environment`、PIT（`pit-xml`）は `mutationScope` が必須。oasdiff は比較元に定義が無かったことを `baseSpecMissing: true` で申告できる。SARIF は走査した対象を `scanners` で申告する |

**この時点ではパースしない**（受領・検証・保存だけ）。同じ種別・同じファイル名の再送は置き換える。
必須のメタデータは取り込み時に検証して拒否する。判定時に気づいても収集ランナーのログには残らないため。

### 3.3 `POST /api/v1/runs/{runId}/finalize`

取り込みを確定し、**その場で**合格ラインの解決・正規化・判定を行い、`status` / `verdict` / `completeness` を返す（DD-15）。
判定に失敗しても 200 で、`status` が `FAILED`、`errorCode` に理由（`CONFIG_VALIDATION_FAILED` / `EVALUATION_FAILED`）が入る。
収集ランナーは `FAILED` ならワークフローを失敗にし、判定結果の `FAIL` では失敗にしない。

---

## 4. 参照 API の設計判断

| 項目 | 判断 |
| --- | --- |
| Run 一覧のカーソル | `(measuredAt, id)` のキーセット。時系列に増え続けるため、オフセットでは送り中に重複・欠落が起きる。違反一覧は判定時に確定したスナップショットなのでオフセット |
| 絞り込みの未知の値 | `state` / `severity` に未知の値を渡したら 400。黙って無視すると「絞り込んだのに全件出た」ように見える |
| 計測条件 | 指標行は計測条件の生の値（`variant`）と表示名（`variantLabel`）を返す。前回値と差は条件の一致する比較対象からだけ出す |
| トレンドの系列 | コンポーネントと計測条件（`variant`）で分ける。コンポーネントや条件を持たない点（未提出の ERROR、SKIP）は既存の系列に欠測として配り、新しい系列を作らない。`NOT_APPLICABLE` の点と判定できていない Run は返さない。色は `colorIndex` としてサーバが固定する。期間内でしきい値が変わっていれば `thresholdChanged` で示す |
| 画面の要素を指す違反 | M-07〜M-10 の違反はリポジトリ上のファイルでないため、`filePath` / `line` / `sourceUrl` は `null`。位置は `detail`（ページと CSS セレクタ、テスト名、API のパスなど）で示す |
| リリース判定の `ref` | 16 進 7〜40 桁ならコミット SHA とみなし、計測済みの Run から前方一致で探す（複数一致は 400）。それ以外はタグとみなし、そのタグを付けて計測した Run のコミットに解決する。ブランチ名は受け付けない（先頭が動くため証跡にならない）。未計測でも 200（`decision: UNDETERMINED`）で返す |
| 設定（S-06） | 表示だけ。直近の Run に送られた合格ラインを検証し直し、行番号つきのエラーを返す（画面が該当行の直下にエラーを出すため） |
| 利用者 | 自分自身の降格・無効化と、有効な管理者が 0 人になる変更は 409。GitHub のユーザーの実在は確認しない（外部の障害に巻き込まれないため。存在しない名前はログインできないだけ） |
| 成果物 | 常にダウンロード（`Content-Disposition: attachment`）で返す。実体が削除済みなら 409 |

---

## 5. 認可マトリクス

| 操作 | VIEWER | ADMIN | Ingest Token |
| --- | :---: | :---: | :---: |
| 閲覧（ダッシュボード・Run・違反・トレンド・設定・リリース判定・成果物）と CSV の出力 | ○ | ○ | — |
| Run の作成・成果物の送信・確定 | — | — | ○ |
| 再評価 | — | ○ | — |
| 利用者・ロールの管理、監査ログの閲覧 | — | ○ | — |

---

## 6. エラーコード

| `errorCode` | HTTP | 意味 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 入力の検証エラー（`violations` に詳細） |
| `UNAUTHENTICATED` / `TOKEN_INVALID` | 401 | 未認証 / Ingest Token が不正 |
| `USER_NOT_ALLOWLISTED` / `USER_DISABLED` / `FORBIDDEN` | 403 | 許可リストに無い / アカウントが無効 / 権限不足 |
| `RESOURCE_NOT_FOUND` | 404 | 対象が無い（存在しない URL も含む） |
| `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` | 405 / 406 | 使えないメソッド / 応答できない形式 |
| `RUN_ALREADY_FINALIZED` | 409 | 確定済みの Run への操作 |
| `RUN_NOT_EVALUABLE` | 409 | 確定していない Run の再評価 |
| `ARTIFACTS_DELETED` | 409 | 成果物が保持期間で削除済み |
| `USER_ALREADY_EXISTS` / `ADMIN_REQUIRED` | 409 | 同じ利用者がいる / 管理者がいなくなる変更 |
| `ARTIFACT_TOO_LARGE` | 413 | 1 ファイル 50MB、または Run 合計 200MB の超過 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 本文の形式に対応していない |
| `ARTIFACT_TYPE_UNKNOWN` / `ARTIFACT_FORMAT_INVALID` | 422 | 未知の成果物種別 / パースに失敗 |
| `PERFORMANCE_METADATA_MISSING` / `MUTATION_SCOPE_MISSING` | 422 | 性能の `environment` / PIT の `mutationScope` が無い |
| `CONFIG_VALIDATION_FAILED` | 422 | 合格ラインの検証エラー（Run の処理失敗の理由にも使う） |
| `INTERNAL_ERROR` | 500 | 想定外の例外 |

---

## 7. OpenAPI 仕様の生成

springdoc がコントローラと DTO から `api/openapi.yml` を生成し、リポジトリにコミットする。
フロントエンドはそこから型を生成する（[API の型生成](../development/api-codegen.md)）。PR の CI は再生成して差分が無いことを確かめる。

- エンティティをそのまま返さない（DB の変更が API の破壊的変更に直結するため）
- 入れ子の型には応答をまたいで一意な名前を付ける（springdoc はスキーマ名に Java の単純名を使い、同名の型が静かに上書きされる）
- 列挙値は文字列で返す
