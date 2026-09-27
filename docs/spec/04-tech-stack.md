# quality-gate 技術スタック

本書は quality-gate の採用技術・版・構成を定める。

---

## 1. 全体構成

**モノレポ**で、フロントエンドのビルド成果物をバックエンドの静的リソースとして同梱する。
実行時は **Spring Boot 単一プロセス**で、SPA と API が同一オリジンになる。

```
ブラウザ ── / → SPA、/api/v1/... → REST API
   ▼
Spring Boot 4 (Java 25) ──▶ PostgreSQL 17
   └ 成果物ストア（ローカル FS。バインドマウント）
```

同一オリジンにすると、CORS が要らず、認証を HttpOnly / SameSite=Lax のセッション Cookie で完結でき
（ブラウザに認証トークンを置かない）、本番がアプリ 1 コンテナ + DB 1 コンテナで済む。

```
quality-gate/
├ backend/     Spring Boot（src/main/resources/db/migration/ に Flyway）
├ frontend/    Vue 3 SPA（src/api/schema.d.ts は生成物。e2e/ に Playwright + axe-core）
├ api/         openapi.yml（バックエンドから生成）
├ collector/   収集ランナー（計測スクリプト・計測プロファイル・合格ライン・ツールの版）
├ .github/workflows/  collect.yml（収集ランナー）/ ci.yml（PR の CI）
├ compose.yaml
└ Dockerfile   アプリのイメージ（SPA を同梱した jar）
```

---

## 2. バックエンド

| 分類 | 採用技術 | 版 | 備考 |
| --- | --- | --- | --- |
| 言語 | Java | 25 LTS | Temurin |
| フレームワーク | Spring Boot | 4.1.1 | Spring MVC + 仮想スレッド（取り込みは I/O 中心。リアクティブの複雑さを負わない） |
| ビルド | Maven（Wrapper 同梱） | 3.9 以上 | |
| 認証 | Spring Security（OAuth2 Client: GitHub） | — | サーバサイドセッション（Spring Session JDBC） |
| 永続化 | Spring Data JPA / PostgreSQL 17 / Flyway | — | `spring-boot-starter-flyway` が要る（5 章） |
| API 仕様 | springdoc-openapi | 3.1.1 | `api/openapi.yml` の生成元 |
| JSON / XML | Jackson 3（`tools.jackson`）/ StAX | 3.1.5 | XML は XXE と外部 DTD を無効にして読む。JSON は深さとサイズに上限を設ける。いずれもストリーミングで読む |
| 可観測性 | Actuator + Micrometer（Prometheus） | — | |
| 定期処理 | `@Scheduled` | — | 単一プロセスのため ShedLock は不要 |
| テスト | JUnit 5、AssertJ、Mockito、Testcontainers 2.0.5（PostgreSQL）、ArchUnit | — | H2 などの代替 DB は使わない（jsonb・部分インデックス・CHECK 制約を検証するため） |

---

## 3. フロントエンド

| 分類 | 採用技術 | 版 | 備考 |
| --- | --- | --- | --- |
| フレームワーク | Vue（Composition API + `<script setup>`） | 3.5 | |
| 言語 | TypeScript（`strict` + `noUncheckedIndexedAccess`） | 5.x | |
| ビルド / ランタイム | Vite / Node.js | 8.x / 24 LTS | Node.js は `.nvmrc` で固定 |
| UI コンポーネント | PrimeVue + `@primevue/themes` | 4.5.x | アクセシビリティ対応のため。5.x はライセンス条件が変わったため 4.5 系 |
| 状態管理 / ルーティング | Pinia / Vue Router | 4.x / 5.x | |
| API 呼び出し | openapi-typescript + openapi-fetch | — | 4 章 |
| グラフ | インライン SVG（`TrendChart.vue`） | — | canvas はスクリーンリーダーで読めない。SVG なら要素にラベルを付けられ、配色トークンとダークモードに CSS だけで追従し、DOM として検証できる。1〜3 系列・数十点の密度ではライブラリの機能は要らない |
| テスト | Vitest + @vue/test-utils、Playwright + `@axe-core/playwright` | — | |
| Lint | ESLint（`eslint-plugin-vue`）+ Prettier | — | |

---

## 4. バックエンドとフロントエンドの連携（OpenAPI）

**バックエンドを唯一の正本**とし、そこから型を生成する（コードファースト）。

```
コントローラ・DTO ─springdoc→ api/openapi.yml ─openapi-typescript→ frontend/src/api/schema.d.ts ─openapi-fetch→ 型安全な呼び出し
```

- `openapi.yml` は結合テスト（`OpenApiExportIT`）がアプリを起動して書き出す。Testcontainers が既にあるため、専用の起動プロファイルが要らない
- `openapi.yml` と `schema.d.ts` は**どちらもコミットする**。フロントエンドのビルドがバックエンドの起動に依存せず、API の変更がレビューの差分に現れる
- 更新し忘れは PR の CI（`ci.yml`）で再生成して `git diff --exit-code` で検出する（[API の型生成](../development/api-codegen.md)）

### 4.1 開発時と本番

- 開発中は Vite（5173）のプロキシで `/api`・`/oauth2`・`/login/oauth2` を Spring Boot（8080）へ送り、同一オリジンに見せる（`changeOrigin: false`。Cookie と OAuth のリダイレクト先を本番と同じに扱う）。`/login` 全体ではなく `/login/oauth2` に限るのは、`/login` が SPA のログイン画面でもあるため
- 本番ビルドは `frontend-maven-plugin` で `npm ci` と `npm run build` を行い、`frontend/dist` を `target/classes/static` へ置く。バックエンドだけを速く回すときは `-DskipFrontend=true`
- 開発時は `docker compose up -d db` で PostgreSQL だけを起動し、`--profile full` のときだけアプリもコンテナで動かす
- 成果物はローカルファイルシステム（バインドマウント）に置く。この規模ではオブジェクトストレージは過剰。保存先を触るコードは `ArtifactStore` インタフェースの向こうに閉じる
- WSL2 で開発するときは、リポジトリを Linux 側のファイルシステムに置く（`/mnt/c` 配下は I/O が著しく遅い）

---

## 5. 実装上の注意（気づきにくい点）

| 事象 | 対応 |
| --- | --- |
| Spring Boot 4 の JSON は **Jackson 3**。DI されるのは `tools.jackson.databind.ObjectMapper` | `tools.jackson` を使う（例外は非チェック例外の `JacksonException`）。アノテーションは `com.fasterxml.jackson.annotation` のまま |
| **`flyway-core` だけではマイグレーションが実行されない**（Boot 4 は自動設定がモジュール分割されている）。アプリは起動し、テーブルに触れて初めて失敗する | `spring-boot-starter-flyway` を入れ、`FlywayMigrationIT` で検出する |
| `TestRestTemplate` と `@AutoConfigureMockMvc` が無い | `RestClient` と `MockMvcBuilders.webAppContextSetup(...).apply(springSecurity())` を使う |
| Testcontainers 2.x の `PostgreSQLContainer` は非ジェネリック | 型引数を付けずに使う |
| SPA のフォールバックに `/**/{path}` を登録すると起動に失敗する | `PathResourceResolver` で、静的ファイルとして解決できないパスを index.html に解決し直す（`SpaForwardingConfig`）。API のパスは除き、存在しない API に HTML を返さない |
| SPA のパスまで認証必須にすると、URL を直接開いたときに 401 が返る | `/api/**` だけ認証必須（`/actuator/**` は ADMIN、health は公開）。未ログインでもシェルは返し、`/api/v1/me` の 401 で `/login` へ誘導する |
| 複数モジュールを組み立てる設定（`SecurityConfig`）を `platform` に置くと依存規則に違反する | `com.qualitygate.config` に置く |
| springdoc は入れ子レコードのスキーマ名に単純名を使い、同名の型を**静かに上書きする** | 応答をまたいで一意な名前を付け、`OpenApiExportIT` で検証する |
| springdoc は既定で `required` も `nullable` も出さず、生成型が全項目省略可能になる | 必ず返す項目に `@NotNull`、null を返しうる項目に `@Schema(nullable = true)` を付ける |
| 同梱ブラウザを取得できない環境で Playwright が動かない | `QG_E2E_CHROMIUM` に実行ファイルのパスを渡す |

---

## 6. 品質の自己適用とバージョン管理

quality-gate 自身は quality-gate で計測しない（DD-5）。PR の CI（`ci.yml`）で、テスト（JUnit・Vitest）、生成物の同期、
アクセシビリティ（axe-core の critical / serious 0 件、ライト / ダーク）、脆弱性（修正版のある重大・高 0 件）を検査する。

Java は `pom.xml` と CI の `setup-java`、Node.js は `.nvmrc`、依存は Spring Boot の BOM と `package-lock.json`（CI は `npm ci`）で固定する。

---

## 7. 採用しなかった選択肢

| 選択肢 | 理由 |
| --- | --- |
| Gradle | JaCoCo・PIT・springdoc などのプラグイン連携は Maven が最も枯れている |
| nginx での分離配信 / 別オリジン | コンテナが増える、または CORS とクロスサイト Cookie の設計が要る。独立デプロイの必要が無い |
| Orval + TanStack Query / openapi-generator | ライブラリが増える、生成物が大きい。必要になれば openapi-fetch の上に載せられる |
| MinIO（S3 互換） | この規模ではローカルファイルシステムで足りる |
| Spring WebFlux | 仮想スレッドで足りる。リアクティブの学習・デバッグコストに見合わない |
| Chart.js / ECharts | インライン SVG で足りる。密度が足りなくなれば ECharts を再検討する（描画は `TrendChart.vue` に閉じてある） |
