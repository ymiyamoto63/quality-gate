# 取り込み（Ingest API）: 設計

要件は [requirements.md](requirements.md)。送り手の収集ランナーは [アーキテクチャ](../../architecture.md#3-測定の仕組み収集ランナー)、手順は [運用](../../operations.md)。
成果物を誰がどこに作り、どのファイルをどの `type` で送るかは [アーキテクチャ 3.9 成果物の流れ](../../architecture.md#39-成果物の流れ) にある。

## 1. 流れ

```
収集ランナー（submit.sh）
  → POST /api/v1/runs                     Run を作成（初めてのリポジトリは登録）      → 201 {runId}
  → POST /api/v1/runs/{id}/artifacts ×N   検証して保存するだけ（パースしない）          → 202（本文なし）
  → POST /api/v1/runs/{id}/finalize       FINALIZED を確定 → その場で判定             → 200 {status, verdict, errorCode, detailUrl}
```

確定すると判定まで行って結果を返す（DD-15）。判定の中身は [判定](../evaluation/design.md)。

## 2. `POST /api/v1/runs`

| 項目 | 必須 | 備考 |
| --- | --- | --- |
| `repository` | ○ | `owner/name`。`QG_REPOSITORY` と違えば `400 VALIDATION_FAILED`。初めて送られたときに登録する |
| `commitSha` | ○ | 40 桁の 16 進 |
| `branch` / `measuredAt` | ○ | 計測したブランチと日時。比較対象 Run（同じブランチの直前の計測）を探すのと、並べる順に使う |
| `baseCommitSha` | | 比較元（[指標](../../metrics.md#24-比較元と比較対象-run)） |
| `ciRunUrl` | | 計測したワークフローの実行 URL。リリース判定の「計測日時」のリンクになる |
| `tags` | | 計測したコミットを指すタグ（`git tag --points-at`）。リリース判定でタグを解決するのに使う |

同じコミットへの再送信は `attempt` を増やした新しい Run になる。応答は以降の呼び出しに使う `runId` だけ。

## 3. `POST /api/v1/runs/{runId}/artifacts`

| パート | 内容 |
| --- | --- |
| `file` | 成果物 |
| `type` | `jacoco-xml` / `lcov` / `pit-xml` / `k6-summary` / `sarif` / `pmd-xml` / `eslint-json` / `oasdiff-json` / `axe-json` / `test-junit-xml`（[指標](../../metrics.md#1-指標の一覧)） |
| `component` | `backend` / `frontend`（任意） |
| `metadata` | JSON オブジェクトの文字列 |

| `type` | メタデータ | 無いとき |
| --- | --- | --- |
| `k6-summary` | `environment`（`name` 必須。CPU・メモリ・k6 の版など）。異常終了した回は `aborted: true` | `422 PERFORMANCE_METADATA_MISSING` |
| `oasdiff-json` | 比較元に定義が無かったことを `baseSpecMissing: true` で申告できる | — |
| `sarif` | 走査した対象を `scanners`（`vuln` / `misconfig` / `secret` / `license`）で申告する | すべて M-05 として読む |

- **この時点ではパースしない**（受領・検証・保存だけ）。必須のメタデータは取り込み時に検証して拒否する。判定時に気づいても収集ランナーのログに残らないため
- 確定前に同じ種別・同じファイル名を再送すると置き換える。古いファイルはコミット後に消す
- 1 ファイル 50MB（`ARTIFACT_TOO_LARGE`）、1 Run 合計 200MB を超えたら拒否する。通信としての上限（`spring.servlet.multipart`）はそれより少し大きい 60MB にし、理由のわかるエラーを返せるようにしている

**保存先**: `QG_ARTIFACT_ROOT/<runId>/<成果物 ID>_<ファイル名>`（`ArtifactStore`）。成果物 ID を前に付け、再送や種別違いの同名ファイルが既存のファイルを上書きしないようにする。メタデータ（種別・サイズ・SHA-256・保存場所）は `artifacts` テーブルに記録する。

**保存の順序**: ファイルを先に保存し、成功してから DB に記録する。逆順だと「参照先の無い記録」という扱いにくい壊れ方をする。
記録されずに残ったファイル（孤児）は判定に使われないだけで、自動では消さない（日次バッチは持たない）。

## 4. `POST /api/v1/runs/{runId}/finalize`

取り込みを確定し、その場で合格ラインの解決・正規化・判定を行い、`status` / `verdict` / `errorCode` / `detailUrl` を返す。
`detailUrl`（`QG_BASE_URL` から組み立てる、そのコミットのリリース判定の URL `/?ref=<commitSha>`）は、収集ランナーのログに直リンクを出すため。

- 確定は判定の前に別のトランザクションで行い、判定に失敗しても取り消さない（Run を `FAILED` として残す）
- 判定に失敗しても 200 で、`status` が `FAILED`、`errorCode` に理由（`EVALUATION_FAILED`）が入る
- 確定済みの Run への `finalize` は `409 RUN_ALREADY_FINALIZED`（二重に判定しない）
- 収集ランナーは `FAILED` ならワークフローを失敗にし、判定結果の `FAIL` では失敗にしない

## 5. Ingest Token

- バックエンドの環境変数 `QG_INGEST_TOKEN` に設定した値だけを受け付ける。カンマ区切りで複数並べると、どれでも受け付ける（交換のため）
- 送られた値と設定値をどちらも SHA-256 にしてから定数時間で比べる。未設定なら取り込み API はすべて 401
- **書き込み専用**で参照 API は呼べない。漏えいしたときの影響を「偽の計測結果を送れる」に限る
- 交換の手順は [運用](../../operations.md#23-ingest-token)

## 6. 手元で取り込みを試す

`.env` に `QG_INGEST_TOKEN` を設定してバックエンドを起動すれば、curl で試せる。リポジトリは最初の Run 作成で登録される。

```bash
TOKEN=<.env の QG_INGEST_TOKEN>
RUN_ID=$(curl -s -X POST http://localhost:8080/api/v1/runs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"repository\":\"example/sample\",\"commitSha\":\"$(git rev-parse HEAD)\",
       \"branch\":\"main\",
       \"measuredAt\":\"$(date -u +%FT%TZ)\"}" | sed -E 's/.*"runId":"([^"]+)".*/\1/')

curl -s -X POST "http://localhost:8080/api/v1/runs/$RUN_ID/artifacts?type=jacoco-xml&component=backend" \
  -H "Authorization: Bearer $TOKEN" -F file=@path/to/jacoco.xml
curl -s -X POST "http://localhost:8080/api/v1/runs/$RUN_ID/finalize" -H "Authorization: Bearer $TOKEN"   # 判定結果が返る
```

合格ラインはアプリの環境変数（`QG_*`）で決まり、送っていない成果物の指標は ERROR になる（`QG_DISABLED_METRICS` で外した指標を除く）。収集ランナーと同じ流れを手元で動かす方法は [運用](../../operations.md#6-手元で試す)。
