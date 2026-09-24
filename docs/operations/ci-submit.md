# CI から送る: quality-gate-action と CLI

対象の CI が自分で計測して quality-gate に送るための道具です（FR-03-4 / FR-03-5）。
対象リポジトリの計測は、通常は quality-gate 側の[収集ランナー](collector.md)が行います（D-16）。
ここで扱う道具は、収集ランナーが扱えない構成の対象のように、**CI から直接送る場合**に使います。

| 道具 | 置き場所 | 使いどころ |
| --- | --- | --- |
| `quality-gate-action` | [quality-gate-action/](../../quality-gate-action/action.yml) | GitHub Actions。コミット・ブランチ・PR・ランナー種別・比較元・実行の URL を自動で埋める |
| CLI（`qg-submit`） | [cli/qg-submit](../../cli/qg-submit) | GitHub Actions 以外の CI（GitLab CI、Jenkins など）と手元。bash・`curl`・`jq` だけで動く 1 ファイル（FR-03-5 の「単一バイナリ / jar」の代わりに、CI のイメージにたいてい入っているものだけで動くスクリプトにした） |

どちらも Run の作成 → 成果物のアップロード → finalize を 1 回で行います（[取り込み（Ingest API）](ingest.md)）。
リポジトリの登録と Ingest Token の発行、`.quality-gate.yml` の置き方は [CI から直接送る方式](target-repository.md) を参照してください。

## quality-gate-action

```yaml
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0              # PR の比較元（merge-base）を求めるため

      # ...テストと計測...

      - name: quality-gate へ送信
        if: always()                  # テストが落ちてもレポートは送る
        uses: ymiyamoto63/quality-gate/quality-gate-action@main
        with:
          base-url: ${{ vars.QG_BASE_URL }}
          token: ${{ secrets.QG_INGEST_TOKEN }}
          artifacts: |
            jacoco-xml backend/target/site/jacoco/jacoco.xml component=backend
            lcov reports/frontend-coverage/lcov.info component=frontend
            junit-xml backend/target/failsafe-reports/TEST-*ApiIT.xml component=backend
            pit-xml backend/target/pit-reports/mutations.xml component=backend metadata={"mutationScope":"all"}
          skipped-metrics: |
            M-03 性能検証環境が無いため
```

quality-gate は private リポジトリです。別のリポジトリから `uses:` で参照するには、quality-gate の
**Settings → Actions → General → Access** で「同じ owner のリポジトリからのアクセス」を許可してください。
許可しない場合は、`quality-gate-action/` と `cli/` を対象リポジトリに写して `uses: ./quality-gate-action` で使えます。

### 入力

| 入力 | 既定値 | 説明 |
| --- | --- | --- |
| `base-url` | （必須） | quality-gate の URL |
| `token` | （必須） | 対象リポジトリの Ingest Token。Secrets から渡す |
| `artifacts` | 空 | 送る成果物。1 行に 1 つ `type パス [component=名前] [scope=head\|base] [metadata=JSON]`。パスはグロブ可で、一致したファイルを 1 つずつ送る。metadata の JSON には空白を含めない |
| `config` | `.quality-gate.yml` | 判定に使う設定ファイル。空文字なら送らず、画面（S-06）で保存した設定で判定される。ファイルが無ければ警告して送らない |
| `skipped-metrics` | 空 | 計測しなかった指標。1 行に 1 つ `指標 ID 理由`（FR-03-9）。`.quality-gate.yml` の `execution.skippable_metrics` に無い指標は ERROR になる |
| `runner-type` | 実行中のランナー | `self-hosted` / `github-hosted`（FR-03-10）。空なら `RUNNER_ENVIRONMENT` から決める |
| `base-commit-sha` | 自動 | 比較元。空なら PR ではマージ先との merge-base（`fetch-depth: 0` が必要）、push では直前のコミット |
| `allow-missing` | `true` | 成果物のファイルが無くても送る。未提出の指標は quality-gate が ERROR にする（fail-closed） |
| `wait-seconds` | `0` | finalize の後、判定が終わるまで待つ秒数。待つと `verdict` が出力される |
| `fail-on-error` | `false` | 送信に失敗したらステップを失敗させる。既定では警告にとどめ、quality-gate の障害で CI を止めない（NFR 10.3） |

### 出力

| 出力 | 内容 |
| --- | --- |
| `run-id` | 作成した Run の ID |
| `status` | `wait-seconds` を指定したときの処理状態（`EVALUATED` / `FAILED` など） |
| `verdict` | `wait-seconds` を指定し、判定が終わったときの判定結果（`PASS` / `PASS_WITH_WARNINGS` / `FAIL`） |

判定でマージを止めることはしません（D-4）。`verdict` を使って CI を失敗させるかどうかは、呼び出す側の判断です。

## CLI（qg-submit）

GitHub Actions 以外の CI では CLI を直接呼びます。`cli/qg-submit` を取得して実行してください（bash 4 以上・`curl`・`jq` が必要）。

```bash
export QG_BASE_URL=https://qg.example.com
export QG_INGEST_TOKEN=qg_xxxxxxxx_xxxxxxxx   # 引数で渡すとプロセス一覧に出るため、環境変数で渡す

./cli/qg-submit \
  --repository owner/name --commit "$(git rev-parse HEAD)" --branch main --runner-type self-hosted \
  --base-commit "$(git rev-parse HEAD~1)" \
  --config .quality-gate.yml \
  --artifact jacoco-xml=backend/target/site/jacoco/jacoco.xml --component backend \
  --artifact 'junit-xml=backend/target/failsafe-reports/TEST-*ApiIT.xml' --component backend \
  --artifact pit-xml=backend/target/pit-reports/mutations.xml --component backend --metadata '{"mutationScope":"all"}' \
  --skip 'M-03=性能検証環境が無いため' \
  --wait 120
```

- `--component` / `--scope` / `--metadata` は直前の `--artifact` に付きます
- グロブはシェルに展開させず、引用符で囲んで渡します（CLI が展開し、一致したファイルを 1 つずつ送る）
- 成果物のファイルが無いと、Run を作る前に止まります（終了コード 2）。未提出のまま送るなら `--allow-missing` を付けます
- API のエラーは `errorCode` と内容を表示して止まります（終了コード 1。FR-03-7）。入力の誤りは項目ごとに表示します
- レート制限（429）と一時的な障害（5xx、接続の失敗）は、`Retry-After` に従って 3 回まで再試行します
- 途中の成果物の送信で止まった Run は finalize されず、日次バッチで `ABANDONED` になります。直して送り直すと、同じコミットの新しい試行として記録されます（FR-03-6）
- `--wait` を付けると判定の結果（`status` / `verdict`）を表示します。時間内に終わらなければ終了コード 3
- すべての引数は `qg-submit --help` で表示できます

| 終了コード | 意味 |
| --- | --- |
| 0 | 成功 |
| 1 | 送信の失敗（接続できない、API がエラーを返した） |
| 2 | 引数の誤り、成果物が無い、`curl` / `jq` が無い |
| 3 | `--wait` の時間内に判定が終わらなかった |

収集ランナーの送信（`collector/bin/submit.sh`）は、計測プロファイルと `reports/` の置き方を前提にした専用のスクリプトで、この CLI は使っていません。
