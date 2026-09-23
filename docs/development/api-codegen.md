# API の型生成

バックエンドが `api/openapi.yml` を生成し、フロントエンドがそこから型を生成します。
**両方ともリポジトリにコミットします。**

```bash
cd backend && ./mvnw verify          # api/openapi.yml を再生成
cd ../frontend && npm run generate:api   # src/api/schema.d.ts を再生成（prettier で整形まで行う）
git diff --exit-code api/ frontend/src/api/schema.d.ts   # ずれていないか検証
```

main への push で動く計測ワークフロー（`quality-gate.yml` の `base` ジョブの「生成物の同期検証」）でも同じ検証を行い、差分があればジョブを失敗させます。
Pull Request の CI（`ci.yml`）はユニットテストだけを実行し、この検証は行わないため、PR を出す前に手元で確かめてください。

`frontend/e2e/fixtures/` の応答例も同じ扱いの生成物です。
結合テスト（`RunQueryApiIT` / `TrendApiIT` / `WaiverApiIT` / `AdminApiIT` / `RepositoryAdminApiIT`）が実物の API から書き出し、
アクセシビリティ検査がそれを読んで画面を描きます。

書き出す際、UUID と処理時刻は固定値へ置き換えます（`FixtureWriter`）。
そのままだと実行のたびに差分が出て、生成物なのに
「再生成して差分がないこと」を検証できなくなるためです。
