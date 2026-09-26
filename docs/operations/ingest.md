# 取り込み（Ingest API）と判定

計測結果は Ingest API で quality-gate に送ります。送り手は収集ランナーです
（`collector/bin/submit.sh`。[収集ランナーで計測する](collector.md)）。

取り込みから表示までの流れ:

1. 送り手が `POST /api/v1/runs` で Run を作成する（`repository` は quality-gate に登録済みで、有効である必要がある）
2. `POST /api/v1/runs/{runId}/artifacts` で成果物（`jacoco-xml` / `pit-xml` / `sarif` / `pmd-xml` / `quality-gate-config` /
   `git-renames`（ファイルの移動。[指標仕様書 0.4](../spec/02-metrics-spec.md)）など）を
   アップロードする。この時点ではパースせず、`QG_ARTIFACT_ROOT` に保存するだけ
3. `POST /api/v1/runs/{runId}/finalize` で完了を宣言すると、その場で設定の解決 → 正規化 → 判定 → 読み取りモデル更新を行い、
   判定結果（`status` / `verdict` / `completeness`）を返す（[03](../spec/03-design-decisions.md) DD-15）。判定に失敗した場合も 200 で、`status` が `FAILED` になる
4. 画面（Run 詳細 / 違反一覧 / トレンド / ダッシュボード）に結果が表示される

認証には Ingest Token を使います。トークンはバックエンドの環境変数 `QG_INGEST_TOKEN` に設定した 1 つだけで、
収集ランナーの Secret `QG_INGEST_TOKEN` に同じ値を入れます（DD-20）。セッション Cookie との使い分けは
[認証と GitHub App](../architecture/authentication.md#認証の経路) を参照してください。

## Ingest Token の作成と交換

```bash
openssl rand -hex 32   # この値をバックエンドの QG_INGEST_TOKEN と、収集ランナーの Secret QG_INGEST_TOKEN に入れる
```

交換するときは、送信を止めないよう次の順で行います。

1. バックエンドの `QG_INGEST_TOKEN` を `<古い値>,<新しい値>` にして再起動する（両方を受け付ける）
2. 収集ランナーの Secret `QG_INGEST_TOKEN` を新しい値にする
3. バックエンドの `QG_INGEST_TOKEN` を新しい値だけにして再起動する

## ローカルで取り込みを試す

`.env` に `QG_INGEST_TOKEN` を設定してバックエンドを起動し、ADMIN でログインして
**管理 › リポジトリ管理（S-07）** からリポジトリを登録します。その後、たとえば次のように取り込みを試せます
（`./mvnw verify` 済みで JaCoCo のレポートがある前提）。

```bash
TOKEN=<.env の QG_INGEST_TOKEN>
RUN_ID=$(curl -s -X POST http://localhost:8080/api/v1/runs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"repository\":\"ymiyamoto63/quality-gate\",\"commitSha\":\"$(git rev-parse HEAD)\",
       \"branch\":\"main\",\"triggeredBy\":\"local\",
       \"measuredAt\":\"$(date -u +%FT%TZ)\"}" | sed -E 's/.*"runId":"([^"]+)".*/\1/')

curl -s -X POST "http://localhost:8080/api/v1/runs/$RUN_ID/artifacts?type=jacoco-xml&component=backend" \
  -H "Authorization: Bearer $TOKEN" -F file=@backend/target/site/jacoco/jacoco.xml
curl -s -X POST "http://localhost:8080/api/v1/runs/$RUN_ID/finalize" -H "Authorization: Bearer $TOKEN"   # 判定結果が返る
```

リクエスト・応答の詳細は `http://localhost:8080/swagger-ui.html` または
[API 設計](../spec/07-api-design.md) を参照してください。
