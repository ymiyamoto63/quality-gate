# 開発環境

quality-gate 自身を開発するための手順とルールです。アプリを本番として動かす手順と計測の実行は [運用](operations.md) にあります。

## 1. 前提

- JDK 25 / Node.js 24（`.nvmrc`）/ Docker
- **WSL2 で作業する場合、リポジトリは Linux 側のファイルシステム（`/home/...`）に置いてください。** `/mnt/c` 配下は I/O が遅く、ビルドと HMR が目に見えて遅くなります

```
quality-gate/
├ backend/     Spring Boot（Maven Wrapper 同梱。src/main/resources/db/migration/ に Flyway）
├ frontend/    Vue 3 SPA（src/api/schema.d.ts は生成物。e2e/ に Playwright + axe-core）
├ api/         openapi.yml（バックエンドから生成）
├ collector/   収集ランナー（計測スクリプト・計測プロファイル・合格ライン・ツールの版）
├ docs/        ドキュメント
├ .github/workflows/  ci.yml（PR の CI）/ collect.yml（収集ランナー）
├ compose.yaml PostgreSQL（プロファイル full でアプリも）
└ Dockerfile   アプリのイメージ（SPA を同梱した jar）
```

## 2. セットアップと起動

**アプリを動かすだけなら次の 3 つで足ります。** `http://localhost:8080` を開いてください。
`spring-boot:run` がフロントエンドのビルドと同梱まで行うため（Node.js も Maven が `backend/target/` に取得します）、`npm run dev` は要りません。

```bash
cp .env.example .env                    # QG_LOGIN_PASSWORD（12 文字以上）を書き入れる（3 章）
docker compose up -d db                 # PostgreSQL だけを起動する
cd backend && ./mvnw spring-boot:run    # 画面も API も http://localhost:8080
```

`.env` に `QG_REPOSITORY` と `QG_LOGIN_PASSWORD` が無いと起動に失敗します（10 章）。

**画面（`frontend/`）を開発するときは**、次の 2 つを別々のターミナルで起動して `http://localhost:5173` を開きます。保存するとブラウザに即時反映（HMR）されます。

```bash
cd backend && ./mvnw spring-boot:run -DskipFrontend=true   # フロントエンドのビルドを飛ばして速く起動する
cd frontend && npm ci && npm run dev                        # /api などは 8080 にプロキシされる
```

| 起動方法 | 起動するもの | 開く URL | 用途 |
| --- | --- | --- | --- |
| A. 分離起動 | `db` コンテナ / `spring-boot:run -DskipFrontend=true` / `npm run dev` | `:5173` | 画面を触りながら開発する。フロントの変更は即時、バックエンドの変更は再起動で反映 |
| B. 同梱起動 | `db` コンテナ / `spring-boot:run` | `:8080` | 本番に近い形での確認。SPA はビルド時点のものが出るため、フロントを直したら再ビルドが要る |
| C. すべてコンテナ | `docker compose --profile full up --build` | `:8080` | JDK / Node.js を入れずに動かす、デモ（[運用](operations.md#21-起動)） |

- `-DskipFrontend=true` で起動しても、以前にビルドした画面が `backend/target/classes/static/` に残っていると 8080 で表示されます。8080 を API だけにしたいときは `./mvnw clean spring-boot:run -DskipFrontend=true`
- 成果物ストア（`QG_ARTIFACT_ROOT`、既定 `./data/artifacts`）の相対パスは起動したディレクトリから解決されます。`backend/` から起動すると `backend/data/artifacts/` です
- 取り込み API を手元で試すときは `.env` に `QG_INGEST_TOKEN` を書きます（[取り込み](features/ingest/design.md#6-手元で取り込みを試す)）。設定値の一覧は [運用](operations.md#22-設定値)

### 同一オリジンの実現方法

quality-gate は SPA と API を**同一オリジン**で動かす前提です（CORS なし、セッション Cookie 認証）。

- **同梱時（B / C）**: Maven の `frontend` プロファイル（`-DskipFrontend` を付けない限り有効）が `frontend-maven-plugin` で `npm ci` → `npm run build` を行い、`frontend/dist/` を `target/classes/static/` にコピーして jar に含めます。
  静的ファイルとして存在しないパス（`/login` など）は `SpaForwardingConfig` が `index.html` を返し、Vue Router に任せます。`api/`・`actuator/`・`v3/`・`swagger-ui` は対象外で、存在しない API には 404 を返します
- **開発時（A）**: ブラウザは 5173 だけを見ます。`vite.config.ts` の `server.proxy` が `/api`（ログイン・ログアウトを含む）と `/actuator` を 8080 へ送ります。`changeOrigin: false` のため、Cookie を本番と同じ条件で確かめられます

## 3. 開発時の設定（`.env`）

リポジトリ直下の `.env` に最低限次を書きます（`.env.example` をコピーすると、like-chatgpt 向けの値が入っています）。

```bash
QG_REPOSITORY=ymiyamoto63/like-chatgpt
QG_LOGIN_USERNAME=quality
QG_LOGIN_PASSWORD=<12 文字以上>
```

画面のログインは、この共有のユーザー名とパスワードで行います（[認証](features/auth/design.md)）。合格ライン（`QG_*`）の一覧は [運用](operations.md#24-合格ライン) にあります。

- バックエンドは起動時に `.env` を読みます（`application.yml` の `spring.config.import`）。`backend/` から起動しても、リポジトリ直下から起動しても見つかります
- `.env` は `.gitignore` 済みです。パスワードや Ingest Token はコミットしないでください
- 値は `KEY=value` の形で書き、引用符で囲まないでください（引用符も値の一部になります）
- 同じ名前の環境変数があれば、そちらが優先されます
- Windows 側のエディタで編集したら改行コードを LF にしてください（CRLF だと値の末尾に `\r` が付きます）

## 4. コマンド

| コマンド | 実行場所 | 何をするか |
| --- | --- | --- |
| `docker compose up -d db` | リポジトリ直下 | PostgreSQL 17 だけを起動する（DB 名・ユーザー・パスワードはいずれも `qualitygate`、ポート 5432、データは名前付きボリューム `pgdata`） |
| `./mvnw verify` | `backend/` | コンパイル → 単体テスト（Surefire）→ jar → 結合テスト（Failsafe、`*IT`）。結合テストは **Testcontainers が専用の PostgreSQL を起動する**ため、`db` コンテナは使いません（Docker が動いていればよい）。あわせて `api/openapi.yml` と `frontend/e2e/fixtures/*.json` を生成する。`-DskipFrontend=true` を付けないとフロントエンドもビルドして同梱する |
| `./mvnw test` | `backend/` | 単体テストだけ（Docker 不要） |
| `./mvnw spring-boot:run` | `backend/` | アプリを 8080 で起動する。起動時に Flyway がマイグレーションを適用する |
| `./mvnw package -DskipTests` | `backend/` | フロントエンドを同梱した実行可能 jar（`target/quality-gate.jar`）を作る |
| `npm ci` | `frontend/` | `package-lock.json` どおりに依存を入れる |
| `npm run dev` | `frontend/` | Vite の dev server を 5173 で起動する |
| `npm run build` | `frontend/` | 型検査（`vue-tsc`）のあと `frontend/dist/` に本番ビルドを出す |
| `npm run typecheck` / `npm run lint` / `npm run format:check` | `frontend/` | 型検査 / ESLint（警告 0 件が条件）/ Prettier の整形漏れの検出。整形は `npm run format` |
| `npm test` | `frontend/` | Vitest の単体テスト |
| `npm run test:a11y` | `frontend/` | Playwright + axe-core のアクセシビリティ検査（6 章） |
| `npm run generate:api` | `frontend/` | `api/openapi.yml` から `src/api/schema.d.ts` を生成する（5 章） |

## 5. API の型と応答例の生成

バックエンドを唯一の正本とし、そこから型を生成します（コードファースト）。**生成物もリポジトリにコミットします**（フロントエンドのビルドがバックエンドの起動に依存せず、API の変更がレビューの差分に現れるため）。

```
コントローラ・DTO ─springdoc→ api/openapi.yml ─openapi-typescript→ frontend/src/api/schema.d.ts ─openapi-fetch→ 型安全な呼び出し
```

```bash
cd backend && ./mvnw verify                  # api/openapi.yml と e2e/fixtures/*.json を再生成（OpenApiExportIT などの結合テスト）
cd ../frontend && npm run generate:api       # src/api/schema.d.ts を再生成（prettier で整形まで）
git diff --exit-code api/ frontend/src/api/schema.d.ts
```

- `frontend/e2e/fixtures/` の応答例も生成物です。結合テスト（`ReleaseReportApiIT`）が実物の API から書き出し、アクセシビリティ検査がそれで画面を描きます。UUID と時刻は固定値に置き換えます（`FixtureWriter`）。そのままだと実行のたびに差分が出て、同期を検証できないためです
- DTO を変えたら、springdoc の注意点（8 章）と [API の共通規則](architecture.md#64-openapi-仕様の生成) に従ってください

## 6. アクセシビリティ検査

```bash
cd frontend && npm run test:a11y     # ライト / ダークの両モードで検査
```

- dev server は起動していなければ Playwright が立ち上げます（起動済みならそれを使います）
- 検査先を変えるときは `QG_E2E_BASE_URL`、同梱のブラウザを取得できない環境では `QG_E2E_CHROMIUM` に Chromium の実行ファイルのパスを渡します
- ログインが要る画面は、**結合テストが実物の API から書き出した応答例**（`e2e/fixtures/`）を `page.route` で返して描きます。手で書いた応答例だと、API が変わっても検査が通り続けてしまうためです
- 検査の前に画面に内容が描かれたことを確かめます（空のページは必ず違反 0 件になる）
- critical / serious の違反があれば失敗し、違反の内容が失敗のメッセージに出ます

| 検査する画面 | spec |
| --- | --- |
| `/login` | `e2e/a11y.spec.ts` |
| リリース判定（リリース不可の状態。主な違反と技術的な定義を開いた状態） | `e2e/authenticated-a11y.spec.ts` |

自動検査で見つかるのは WCAG 違反の一部だけです。キーボード操作とフォーカス順序は手で確かめてください。

## 7. 技術スタック

| 分類 | 採用技術 | 備考 |
| --- | --- | --- |
| 言語 / フレームワーク | Java 25（Temurin）/ Spring Boot 4.1 | Spring MVC + 仮想スレッド（取り込みは I/O 中心。リアクティブの複雑さを負わない） |
| ビルド | Maven（Wrapper 同梱） | |
| 認証 | Spring Security（フォームログイン、共有の 1 アカウント。セッションはメモリ） | |
| 永続化 | Spring Data JPA / PostgreSQL 17 / Flyway | |
| API 仕様 | springdoc-openapi 3.1 | `api/openapi.yml` の生成元 |
| JSON / XML | Jackson 3（`tools.jackson`）/ StAX | |
| 可観測性 | Actuator + Micrometer（Prometheus） | |
| テスト（バックエンド） | JUnit 5、AssertJ、Mockito、Testcontainers 2.0（PostgreSQL）、ArchUnit | H2 などの代替 DB は使わない（jsonb・部分インデックス・CHECK 制約を検証するため） |
| フロントエンド | Vue 3.5（Composition API + `<script setup>`）/ TypeScript 5（`strict` + `noUncheckedIndexedAccess`）/ Vite 8 | |
| UI コンポーネント | PrimeVue 4.5 + `@primevue/themes` | アクセシビリティ対応のため。5.x はライセンス条件が変わったため 4.5 系 |
| 状態管理 / ルーティング | Pinia / Vue Router | |
| API 呼び出し | openapi-typescript + openapi-fetch | |
| テスト（フロントエンド） | Vitest + @vue/test-utils、Playwright + `@axe-core/playwright` | |
| Lint | ESLint（`eslint-plugin-vue`）+ Prettier | |

版の固定: Java は `pom.xml` と CI の `setup-java`、Node.js は `.nvmrc`、依存は Spring Boot の BOM と `package-lock.json`（CI は `npm ci`）。

**採らなかった選択肢**

| 選択肢 | 理由 |
| --- | --- |
| Gradle | この規模では Maven で足り、Spring Boot と周辺プラグインの連携も枯れている |
| nginx での分離配信 / 別オリジン | コンテナが増える、または CORS とクロスサイト Cookie の設計が要る。独立デプロイの必要が無い |
| Orval + TanStack Query / openapi-generator | ライブラリが増える、生成物が大きい。必要になれば openapi-fetch の上に載せられる |
| MinIO（S3 互換） | この規模ではローカルファイルシステムで足りる。保存先を触るコードは `ArtifactStore` の向こうに閉じてある |
| Spring WebFlux | 仮想スレッドで足りる |
| GitHub でのログイン | 経営陣が GitHub のアカウントを持っていないと見られない。画面は見るだけでロールも要らないため、共有のアカウントで足りる |

## 8. 実装上の注意（気づきにくい点）

| 事象 | 対応 |
| --- | --- |
| Spring Boot 4 の JSON は **Jackson 3**。DI されるのは `tools.jackson.databind.ObjectMapper` | `tools.jackson` を使う（例外は非チェック例外の `JacksonException`）。アノテーションは `com.fasterxml.jackson.annotation` のまま |
| **`flyway-core` だけではマイグレーションが実行されない**（Boot 4 は自動設定がモジュール分割されている）。テーブルに触れて初めて失敗する | `spring-boot-starter-flyway` を入れ、`FlywayMigrationIT` で検出する |
| `TestRestTemplate` と `@AutoConfigureMockMvc` が無い | `RestClient` と `MockMvcBuilders.webAppContextSetup(...).apply(springSecurity())` を使う |
| Testcontainers 2.x の `PostgreSQLContainer` は非ジェネリック | 型引数を付けずに使う |
| SPA のフォールバックに `/**/{path}` を登録すると起動に失敗する | `PathResourceResolver` で、静的ファイルとして解決できないパスを index.html に解決し直す（`SpaForwardingConfig`） |
| SPA のパスまで認証必須にすると、URL を直接開いたときに 401 が返る | `/api/**` だけ認証必須にし、未ログインでもシェルは返して `/api/v1/me` の 401 で `/login` へ誘導する |
| ログイン（`POST /api/v1/login`）にも CSRF トークンが要る | 画面は先に `/api/v1/me` を GET して `XSRF-TOKEN` Cookie を受け取り、`X-XSRF-TOKEN` ヘッダで送り返す |
| 複数モジュールを組み立てる設定（`SecurityConfig`）を `platform` に置くと依存規則に違反する | `com.qualitygate.config` に置く |
| springdoc は入れ子レコードのスキーマ名に単純名を使い、同名の型を**静かに上書きする** | 応答をまたいで一意な名前を付け、`OpenApiExportIT` で検証する |
| springdoc は既定で `required` も `nullable` も出さず、生成型が全項目省略可能になる | 必ず返す項目に `@NotNull`、null を返しうる項目に `@Schema(nullable = true)` を付ける |
| 空の環境変数は既定値より優先される | 設定の既定値は `QualityGateProperties` にだけ持ち、`application.yml` は未設定のとき空を渡す（空は null として受け、既定値にする） |
| 同梱ブラウザを取得できない環境で Playwright が動かない | `QG_E2E_CHROMIUM` に実行ファイルのパスを渡す |

## 9. PR の CI（`ci.yml`）

quality-gate 自身は quality-gate で計測せず（DD-5）、PR の CI で次を確かめます。すべて GitHub ホストランナーで動きます。

| ジョブ | 内容 |
| --- | --- |
| バックエンドのテスト | `./mvnw verify -DskipFrontend=true`（単体・結合テスト）と、生成物（`openapi.yml` / `schema.d.ts`）の同期検証 |
| フロントエンドの検査とユニットテスト | ESLint・Prettier・vue-tsc と Vitest |
| アクセシビリティ検査 | Playwright + axe-core（ライト / ダーク） |
| 脆弱性スキャン | Trivy（依存関係。修正版のある重大・高を 0 件に） |

## 10. 起動時のよくある症状

| 症状 | 原因と対処 |
| --- | --- |
| ヘッダーだけ表示され本文が空のまま。dev server に `http proxy error: /api/v1/me` / `connect ETIMEDOUT 127.0.0.1:8080` | バックエンドに届いていない。起動しているか確かめる。WSL2 では Vite とバックエンドを**同じ環境**で動かす。Windows 側の IDE でバックエンドを動かすなら `.wslconfig` に `networkingMode=mirrored` を設定する |
| 起動時に `QG_REPOSITORY に計測対象のリポジトリを…` で失敗する | `.env` に `QG_REPOSITORY=owner/name` が無い |
| 起動時に `QG_LOGIN_PASSWORD に…` / `12 文字以上にしてください` で失敗する | `.env` の `QG_LOGIN_PASSWORD` が無いか短い |
| 起動時に `QG_ACCESSIBILITY_STANDARD は…`・数値の変換エラーで失敗する | 合格ライン（`QG_*`）の値の形式が不正 |
| ログインしても「ユーザー名かパスワードが違います」と出る | `QG_LOGIN_USERNAME`（既定 `quality`）/ `QG_LOGIN_PASSWORD` と違う。アプリを再起動するとセッションが消え、ログインし直しになる |

疎通は Vite を動かしているのと同じ端末から `curl http://127.0.0.1:8080/actuator/health` で確かめられます。
