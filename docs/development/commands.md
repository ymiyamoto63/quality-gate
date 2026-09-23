# コマンド一覧

## 基本のコマンド

| コマンド | 実行場所 | 何をするか |
| --- | --- | --- |
| `docker compose up -d db` | リポジトリ直下 | `compose.yaml` の `db` サービス（PostgreSQL 17）だけをバックグラウンドで起動する。DB 名・ユーザー・パスワードはいずれも `qualitygate`、ポートは `5432`。データは名前付きボリューム `pgdata` に残るため、コンテナを作り直しても消えない（消すときは `docker compose down -v`）。`app` サービスは `full` プロファイルに属しているため、ここでは起動しない |
| `./mvnw verify` | `backend/` | バックエンドのコンパイル → 単体テスト（Surefire）→ jar 作成 → 結合テスト（Failsafe、`*IT`）までを行う。結合テストは **Testcontainers が専用の PostgreSQL コンテナを別途起動する**ため、[セットアップ](setup.md)の手順 1 の DB は使わない（Docker さえ動いていればよい）。あわせて JaCoCo / PMD のレポート、`api/openapi.yml`、`frontend/e2e/fixtures/*.json` を生成する。`frontend` プロファイルが有効なので、フロントエンドのビルドと同梱（[起動の仕組み](../architecture/runtime.md#フロントエンドとバックエンドの連携)）も行われる |
| `./mvnw spring-boot:run` | `backend/` | アプリケーションを `http://localhost:8080` で起動する。起動時に Flyway が `db/migration` の V001〜 を DB に適用し、ジョブワーカー（`JobWorker`、1 秒間隔のポーリング）も同じプロセス内で動き出す。このコマンドもコンパイルまでのフェーズを実行するため、`-DskipFrontend=true` を付けない限りフロントエンドを再ビルドして同梱する |
| `npm ci` | `frontend/` | `package-lock.json` どおりに依存関係を入れ直す（`npm install` と違い lock ファイルを書き換えない） |
| `npm run dev` | `frontend/` | Vite の dev server を `http://localhost:5173` で起動する。`.vue` / `.ts` の変更はブラウザに即時反映（HMR）される。API などは 8080 のバックエンドへプロキシされる（[起動の仕組み](../architecture/runtime.md#フロントエンドとバックエンドの連携)） |

## その他によく使うコマンド

| コマンド | 実行場所 | 何をするか |
| --- | --- | --- |
| `./mvnw test` | `backend/` | 単体テストのみ（Docker 不要） |
| `./mvnw -P mutation test` | `backend/` | PIT によるミューテーションテスト（M-02 の計測元）。時間がかかる |
| `./mvnw package -DskipTests` | `backend/` | フロントエンドを同梱した実行可能 jar（`target/quality-gate.jar`）を作る。`java -jar target/quality-gate.jar` で起動できる |
| `npm run build` | `frontend/` | 型検査（`vue-tsc`）のあと `frontend/dist/` に本番ビルドを出力する |
| `npm run typecheck` / `npm run lint` / `npm run format` | `frontend/` | 型検査 / ESLint（警告 0 件が条件）/ Prettier による整形 |
| `npm test` / `npm run test:coverage` | `frontend/` | Vitest による単体テスト / カバレッジつき（`reports/frontend-coverage/` に出力） |
| `npm run test:a11y` | `frontend/` | Playwright + axe-core によるアクセシビリティ検査（dev server は未起動なら自動で立ち上がる）。結果を `reports/axe-results.json`（M-10 の成果物）に書き出す |
| `docker compose --profile full up --build` | リポジトリ直下 | DB とアプリの両方をコンテナで起動する（[起動の仕組み](../architecture/runtime.md#起動方法の-3-パターン)を参照） |
