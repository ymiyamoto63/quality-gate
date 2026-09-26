# 取り込み（Ingest API）と判定ジョブ

計測結果は Ingest API で quality-gate に送ります。送り手は収集ランナーです
（`collector/bin/submit.sh`。[収集ランナーで計測する](collector.md)）。対象の CI から送るための補助は D-19 で削除しました。

取り込みから表示までの流れ:

1. 送り手が `POST /api/v1/runs` で Run を作成する（`repository` はトークンの発行元と一致する必要がある）
2. `POST /api/v1/runs/{runId}/artifacts` で成果物（`jacoco-xml` / `pit-xml` / `sarif` / `pmd-xml` / `quality-gate-config` など）を
   アップロードする。この時点ではパースせず、`QG_ARTIFACT_ROOT` に保存するだけ
3. `POST /api/v1/runs/{runId}/finalize` で完了を宣言すると、判定ジョブが DB のジョブキューに積まれ、すぐに `202` が返る
4. 同じプロセス内の `JobWorker` がキューを 1 秒間隔でポーリングし（`FOR UPDATE SKIP LOCKED`）、
   正規化 → 判定 → 読み取りモデル更新を行う
5. 画面（Run 詳細 / 違反一覧 / トレンド / ダッシュボード）に結果が表示される

認証の経路（Ingest Token とセッション Cookie の使い分け）は [認証と GitHub App](../architecture/authentication.md#認証の経路) を参照してください。

## ローカルで取り込みを試す

リポジトリの登録と Ingest Token の発行は、ADMIN でログインして **管理 › リポジトリ管理（S-08）** から行えます
（トークンは発行時に一度だけ表示されます）。画面を使わずに登録する場合は、
**一度ログインして ADMIN を作った後に** SQL で直接登録します。

```bash
# トークンは qg_<prefix>_<secret> の形式。prefix は 8 文字以内で一意、DB にはトークン全体の SHA-256 を保存する
TOKEN=qg_local01_$(openssl rand -hex 16)
HASH=$(printf %s "$TOKEN" | sha256sum | cut -d' ' -f1)
echo "$TOKEN"   # 控えておく（DB には残らない）

docker compose exec -T db psql -U qualitygate -d qualitygate <<SQL
INSERT INTO repositories (id, owner, name, created_by)
  SELECT gen_random_uuid(), 'ymiyamoto63', 'quality-gate', id FROM users WHERE role = 'ADMIN' LIMIT 1;
INSERT INTO ingest_tokens (id, repository_id, token_prefix, token_hash, description, created_by)
  SELECT gen_random_uuid(), r.id, 'local01', '$HASH', 'local dev', r.created_by
  FROM repositories r WHERE r.owner = 'ymiyamoto63' AND r.name = 'quality-gate';
SQL
```

登録後は、たとえば次のように取り込みを試せます（`./mvnw verify` 済みで JaCoCo のレポートがある前提）。

```bash
RUN_ID=$(curl -s -X POST http://localhost:8080/api/v1/runs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"repository\":\"ymiyamoto63/quality-gate\",\"commitSha\":\"$(git rev-parse HEAD)\",
       \"branch\":\"main\",\"triggeredBy\":\"local\",
       \"measuredAt\":\"$(date -u +%FT%TZ)\"}" | sed -E 's/.*"runId":"([^"]+)".*/\1/')

curl -s -X POST "http://localhost:8080/api/v1/runs/$RUN_ID/artifacts?type=jacoco-xml&component=backend" \
  -H "Authorization: Bearer $TOKEN" -F file=@backend/target/site/jacoco/jacoco.xml
curl -s -X POST "http://localhost:8080/api/v1/runs/$RUN_ID/finalize" -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/runs/$RUN_ID/status" -H "Authorization: Bearer $TOKEN"
```

リクエスト・応答の詳細は `http://localhost:8080/swagger-ui.html` または
[API 設計](../initial/07-api-design.md) を参照してください。
