# 対象リポジトリの前提と最小構成での取り込み

計測対象のリポジトリを 1 つ登録し、**ブランチカバレッジ（M-01）だけ**を取り込んで判定が出るところまでを確認する手順です。
ほかの指標は、動作を確認してから 1 つずつ有効にしていきます。

取り込みの仕組みは [CI からの取り込み](ingest.md)、各指標の定義は [指標・判定仕様](../initial/02-metrics-spec.md) を参照してください。
全指標を計測する具体例は [like-chatgpt を計測する手順](measure-like-chatgpt.md) にあります。

## 対象リポジトリの前提

quality-gate は対象リポジトリを**読みに行きません**。計測は対象リポジトリ側（CI やローカル）で行い、
その成果物を quality-gate の Ingest API へ送ります。そのため、対象リポジトリに必要なのは次のことだけです。

| 前提 | 理由 |
| --- | --- |
| GitHub（github.com）上にあり、`owner/name` で特定できる | 登録時の識別子であり、画面のコミット・ファイルへのリンクも `https://github.com/<owner>/<name>` で組み立てる |
| 計測したコミットの SHA（40 桁）が分かる | Run はコミット単位で作られる。短縮 SHA は受け付けない |
| 対応形式のレポートを 1 つ以上出力できる | 最小構成では JaCoCo XML（Java）か lcov（JavaScript / TypeScript）を使う。形式の一覧は[後述](#対応している成果物の形式) |
| レポートを送る環境から quality-gate の URL に HTTP で到達できる | 送信は CI（またはローカル）から quality-gate への片方向。quality-gate がローカルで動いているなら、送信もローカルかセルフホストランナーから行う |
| リポジトリのルートに `.quality-gate.yml` を置ける（推奨） | 合格ラインと、判定に使う指標を決める。無い場合の扱いは[手順 2](#2-quality-gateyml-を置く) |

GitHub App へのインストールや、対象リポジトリへのアクセス権の付与は**不要**です
（GitHub App はログインにだけ使います。[認証と GitHub App](../architecture/authentication.md)）。

## 1. リポジトリを登録して Ingest Token を発行する

1. quality-gate に ADMIN でログインする（最初にログインしたユーザーが ADMIN になる。[セットアップ](../development/setup.md)）
2. **管理 › リポジトリ管理（S-08）** で `owner` と `name` を入力してリポジトリを登録する
3. 同じ画面で Ingest Token を発行し、控えておく（`qg_<prefix>_<secret>` の形式。**発行時に一度だけ**表示される）

Ingest Token は登録したリポジトリ専用の書き込み用トークンです。
Run 作成時の `repository` がトークンの発行元と一致しないと拒否されます（`REPOSITORY_MISMATCH`）。
画面を使わずに SQL で登録する方法は [CI からの取り込み](ingest.md#ローカルで取り込みを試す) にあります。

## 2. `.quality-gate.yml` を置く

設定ファイルを送らなかった場合は、画面（S-06）で保存した設定、それも無ければシステム既定値で判定します。
**既定値では 7 つの指標グループがすべて有効**で、成果物が届かなかった指標は ERROR になり、
Run 全体が不合格（FAIL）になります（fail-closed）。

最小構成では、使わない指標を `enabled: false` で明示的に外します。
対象リポジトリのルートに次の内容で `.quality-gate.yml` を置いてください。

```yaml
version: 1
enforcement: report-only   # まだ CI を止めない

metrics:
  branch_coverage:
    enabled: true
    threshold: 75
  mutation_score:        { enabled: false }
  performance:           { enabled: false }
  vulnerabilities:       { enabled: false }
  cyclomatic_complexity: { enabled: false }
  api_contract:          { enabled: false }
  accessibility:         { enabled: false }
```

- `version: 1` は必須です
- 未知のキーはエラーになります（typo で設定が効かないまま合格が出るのを防ぐため）。
  設定エラーの Run は判定されず、`status=FAILED`・`errorCode=CONFIG_VALIDATION_FAILED` になります（エラー箇所は行番号付きで記録される）
- `on_missing_report: warn` を書いても、現状は未提出の指標は ERROR のままです。外したい指標は `enabled: false` にしてください
- 除外パターン（`exclusions`）は、各ツールが報告するパスの形に合わせて書きます。
  書き方は quality-gate 自身の [.quality-gate.yml](../../.quality-gate.yml) のコメントを参照してください

## 3. 成果物を送る

送るのは次の 4 回の呼び出しです。`jacoco-xml` を lcov に替える場合は `type=lcov` にして lcov.info を送ります。

| 順 | 呼び出し | 内容 |
| --- | --- | --- |
| 1 | `POST /api/v1/runs` | Run を作成し、`runId` を受け取る |
| 2 | `POST /api/v1/runs/{runId}/artifacts?type=quality-gate-config` | `.quality-gate.yml` を送る。判定はそのコミットの設定で行う |
| 3 | `POST /api/v1/runs/{runId}/artifacts?type=jacoco-xml` | カバレッジのレポートを送る |
| 4 | `POST /api/v1/runs/{runId}/finalize` | 取り込み完了を宣言する。判定は非同期で始まり、すぐに `202` が返る |

### ローカルから送る

対象リポジトリのディレクトリで、テストを実行して JaCoCo のレポートを出した後に実行します。

```bash
QG=http://localhost:8080          # quality-gate の URL
TOKEN=qg_xxxxxxxx_xxxxxxxx        # 手順 1 で発行した Ingest Token
REPO=OWNER/NAME                   # 手順 1 で登録した owner/name
REPORT=target/site/jacoco/jacoco.xml

RUN_ID=$(curl -sf -X POST "$QG/api/v1/runs" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"repository\":\"$REPO\",\"commitSha\":\"$(git rev-parse HEAD)\",
       \"branch\":\"$(git branch --show-current)\",\"runnerType\":\"self-hosted\",
       \"triggeredBy\":\"local\",\"measuredAt\":\"$(date -u +%FT%TZ)\"}" | jq -r .runId)

curl -sf -X POST "$QG/api/v1/runs/$RUN_ID/artifacts?type=quality-gate-config" \
  -H "Authorization: Bearer $TOKEN" -F file=@.quality-gate.yml
curl -sf -X POST "$QG/api/v1/runs/$RUN_ID/artifacts?type=jacoco-xml" \
  -H "Authorization: Bearer $TOKEN" -F file=@"$REPORT"
curl -sf -X POST "$QG/api/v1/runs/$RUN_ID/finalize" -H "Authorization: Bearer $TOKEN"
```

### GitHub Actions から送る

CI 用の送信アクション（`quality-gate-action`）は未実装のため、当面は `curl` で送ります。
リポジトリの Secrets に `QG_INGEST_TOKEN`、Variables に `QG_BASE_URL`（CI から到達できる quality-gate の URL）を登録し、
テストの後に次のステップを追加します。

```yaml
      - name: quality-gate へ送信
        if: always()                 # テストが落ちてもレポートは送る
        continue-on-error: true      # quality-gate の障害で CI を止めない
        env:
          QG: ${{ vars.QG_BASE_URL }}
          TOKEN: ${{ secrets.QG_INGEST_TOKEN }}
        run: |
          set -euo pipefail
          RUN_ID=$(curl -sf -X POST "$QG/api/v1/runs" \
            -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
            -d "{\"repository\":\"${{ github.repository }}\",\"commitSha\":\"${{ github.sha }}\",
                 \"branch\":\"${{ github.head_ref || github.ref_name }}\",\"runnerType\":\"github-hosted\",
                 \"triggeredBy\":\"${{ github.event_name }}\",
                 \"ciRunUrl\":\"${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}\",
                 \"measuredAt\":\"$(date -u +%FT%TZ)\"}" | jq -r .runId)
          curl -sf -X POST "$QG/api/v1/runs/$RUN_ID/artifacts?type=quality-gate-config" \
            -H "Authorization: Bearer $TOKEN" -F file=@.quality-gate.yml
          curl -sf -X POST "$QG/api/v1/runs/$RUN_ID/artifacts?type=jacoco-xml" \
            -H "Authorization: Bearer $TOKEN" -F file=@target/site/jacoco/jacoco.xml
          curl -sf -X POST "$QG/api/v1/runs/$RUN_ID/finalize" -H "Authorization: Bearer $TOKEN"
```

`runnerType` は実際に動いたランナーに合わせます（`self-hosted` / `github-hosted`）。
M-01 の判定には影響しませんが、性能指標を有効にしたときに判定に使うかどうかがこれで決まります。

## 4. 結果を確認する

```bash
curl -s "$QG/api/v1/runs/$RUN_ID/status" -H "Authorization: Bearer $TOKEN"
```

判定は数秒で終わります。`status` が `EVALUATED` になり、`verdict` が `PASS` / `PASS_WITH_WARNINGS` / `FAIL` のいずれかになれば成功です。
画面ではダッシュボードに対象リポジトリが現れ、Run 詳細に M-01 の実測値としきい値が表示されます。

| 症状 | 主な原因 |
| --- | --- |
| Run 作成が `404` | `repository` の `owner/name` が登録内容と違う |
| Run 作成が `REPOSITORY_MISMATCH` | 別のリポジトリ用の Ingest Token を使っている |
| Run 作成が `403` | 管理画面でリポジトリが無効化されている |
| 判定が FAIL で、M-01 以外の指標が「成果物が提出されていません」 | `.quality-gate.yml` を送っていない、または `enabled: false` にしていない |
| `status` が `FAILED`、`errorCode` が `CONFIG_VALIDATION_FAILED` | `.quality-gate.yml` に未知のキーや範囲外の値がある。エラー箇所は Run 詳細に行番号付きで出る |
| M-01 が「成果物を解釈できませんでした」 | `type` とファイルの形式が合っていない（例: JaCoCo の HTML レポートを送っている） |

## 次の指標を追加する

指標を 1 つ有効にするごとに、`.quality-gate.yml` の `enabled: false` を外し、対応する成果物を送る呼び出しを 1 つ足します。
重い計測（PIT / k6）を毎回は実行しない場合は、`execution.skippable_metrics` に指標名を書いたうえで、
Run 作成時に `skippedMetrics` で申告します（申告のない未提出は ERROR になります）。

### 対応している成果物の形式

| `type` | 指標 | 補足 |
| --- | --- | --- |
| `jacoco-xml` / `lcov` / `istanbul-json` | M-01 ブランチカバレッジ | コンポーネントを分けるなら `component=` を付ける（値は合算しない） |
| `pit-xml` | M-02 ミューテーションスコア | `metadata={"mutationScope":"all"}`（または `changed`）が必須 |
| `k6-summary` | M-03〜05 性能 | `metadata={"environment":{"name":"..."}}` が必須 |
| `sarif` / `osv-json` | M-06 脆弱性 | SARIF は M-07 にも使える |
| `pmd-xml` / `eslint-json` / `lizard-csv` / `sarif` | M-07 循環的複雑度 | |
| `junit-xml` / `pact-verification` | M-08 契約テスト成功率 | |
| `oasdiff-json` | M-09 破壊的変更 | 比較元に定義が無いときは `metadata={"baseSpecMissing":true}` |
| `axe-json` | M-10 アクセシビリティ | `.quality-gate.yml` の `accessibility.pages` の画面がすべて含まれている必要がある |

ファイルサイズの上限は 1 ファイル 50MB、1 Run あたり合計 200MB です。
実際に全指標を送っている例は、quality-gate 自身のワークフロー [.github/workflows/quality-gate.yml](../../.github/workflows/quality-gate.yml) を参照してください。
