# 設定値（環境変数 / `.env`）

| 変数 | 既定値 | 説明 |
| --- | --- | --- |
| `QG_GITHUB_CLIENT_ID` / `QG_GITHUB_CLIENT_SECRET` | ダミー値 | ログイン用 GitHub App の認証情報。未設定でも起動はするが、ログインできない |
| `QG_DB_URL` / `QG_DB_USERNAME` / `QG_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/qualitygate` / `qualitygate` / `qualitygate` | 接続先 DB。`compose.yaml` の `db` と一致している |
| `QG_ARTIFACT_ROOT` | `./data/artifacts` | 成果物の保存先（起動したディレクトリからの相対パス） |
| `QG_BASE_URL` | `http://localhost:8080` | 取り込み API の応答に含める Run 詳細画面の URL の組み立てに使う |
| `QG_SMTP_HOST` / `QG_SMTP_PORT` | なし / `25` | メール通知に使う SMTP リレー。未設定ならメールは送らず、送信履歴に理由を残す |
| `QG_SMTP_USERNAME` / `QG_SMTP_PASSWORD` | なし | SMTP 認証が要る場合のみ |
| `QG_MAIL_FROM` | `quality-gate@localhost` | 通知メールの差出人 |
| `QG_SCHEDULE_ZONE` | `Asia/Tokyo` | 日次バッチ（02:00 再評価・02:10 免除の期限切れ・03:00 保持期間・03:10 滞留 Run・09:00 鮮度確認）のタイムゾーン。各時刻は `quality-gate.schedule.*` の cron 式で変えられる |

優先順位は **環境変数 > `.env` > `application.yml` の既定値**です。

`docker compose --profile full` で動かす場合、`app` コンテナに渡るのは `compose.yaml` に列挙した変数だけです
（`QG_BASE_URL` / `QG_SMTP_USERNAME` / `QG_SMTP_PASSWORD` / `QG_SCHEDULE_ZONE` は渡らないため、使うなら `compose.yaml` に足してください）。
DB 接続先と `QG_ARTIFACT_ROOT` はコンテナ用の値で上書きされます。

保持期間（Run・成果物・監査ログ・通知の日数）は環境変数ではなく、管理画面（S-09）から変更します。
