# 設定値（環境変数 / `.env`）

| 変数 | 既定値 | 説明 |
| --- | --- | --- |
| `QG_GITHUB_CLIENT_ID` / `QG_GITHUB_CLIENT_SECRET` | ダミー値 | ログイン用 GitHub App の認証情報。未設定でも起動はするが、ログインできない |
| `QG_DB_URL` / `QG_DB_USERNAME` / `QG_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/qualitygate` / `qualitygate` / `qualitygate` | 接続先 DB。`compose.yaml` の `db` と一致している |
| `QG_ARTIFACT_ROOT` | `./data/artifacts` | 成果物の保存先（起動したディレクトリからの相対パス） |
| `QG_BASE_URL` | `http://localhost:8080` | 取り込み API の応答に含める Run 詳細画面の URL の組み立てに使う |
| `QG_LOG_FORMAT` | なし（テキスト） | `ecs` / `logstash` / `gelf` で JSON 構造化ログにする。相関 ID（`requestId` / `runId` / `jobId`）が項目として載る。`compose.yaml` の `full` では `ecs` |
| `QG_SCHEDULE_ZONE` | `Asia/Tokyo` | 日次バッチ（03:00 保持期間の削除・03:10 滞留した Run の後始末）とレポートの期間の区切りのタイムゾーン。各時刻は `quality-gate.schedule.*` の cron 式で変えられる |

優先順位は **環境変数 > `.env` > `application.yml` の既定値**です。

`docker compose --profile full` で動かす場合、`app` コンテナに渡るのは `compose.yaml` に列挙した変数だけです
（`QG_BASE_URL` / `QG_SCHEDULE_ZONE` は渡らないため、使うなら `compose.yaml` に足してください）。
DB 接続先と `QG_ARTIFACT_ROOT` はコンテナ用の値で上書きされます。

保持期間（Run・成果物・監査ログの日数）は環境変数ではなく、管理画面（S-09）から変更します。

収集ランナーの設定（取り込み先の URL、GitHub App、Ingest Token）はバックエンドではなく、quality-gate リポジトリの
GitHub Actions の Variables / Secrets に置きます（[収集ランナーで計測する](collector.md#1-3-quality-gate-リポジトリの変数とシークレット)）。
