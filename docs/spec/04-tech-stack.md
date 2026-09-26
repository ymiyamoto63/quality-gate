# quality-gate 技術スタック

本書は quality-gate の採用技術・版・構成を定める（[要件定義書](01-requirements.md) 11 章の詳細）。

---

## 1. 全体構成

quality-gate は**モノレポ**で構成し、フロントエンドのビルド成果物を
バックエンドの静的リソースとして同梱する。実行時は **Spring Boot 単一プロセス**となり、
SPA と API が完全に同一オリジンになる。

```
ブラウザ
   │  https://quality-gate.example/           → SPA（Spring Boot が配信）
   │  https://quality-gate.example/api/v1/... → REST API
   ▼
┌─────────────────────────────────┐     ┌──────────────┐
│  Spring Boot 4 (Java 25)         │────▶│ PostgreSQL 17│
│   ├ SPA（静的リソースとして同梱） │     └──────────────┘
│   ├ REST API                     │
│   └ 成果物ストア（ローカル FS）  │────▶ ./data/artifacts（バインドマウント）
└─────────────────────────────────┘
```

同一オリジンにする利点は次の 3 点。

- **CORS 設定が一切不要**になる
- 認証を **HttpOnly / SameSite=Lax のセッション Cookie** で完結でき、
  ブラウザ側に認証トークンを保存しない（XSS でトークンを盗まれる経路がなくなる）
- 本番の構成要素がアプリ 1 コンテナ + DB 1 コンテナで済む

### ディレクトリ構成

```
quality-gate/
├ backend/
│  ├ src/main/java/                     アプリケーションコード
│  ├ src/main/resources/
│  │   ├ db/migration/                  Flyway マイグレーション
│  │   └ static/                        （ビルド時に frontend/dist が配置される）
│  ├ src/test/java/                     JUnit 5 / Testcontainers
│  └ pom.xml
├ frontend/
│  ├ src/
│  │   ├ api/
│  │   │   ├ schema.d.ts                openapi-typescript の生成物（コミットする）
│  │   │   └ client.ts                  openapi-fetch のクライアント定義
│  │   ├ components/ views/ stores/
│  │   └ main.ts
│  ├ e2e/                               Playwright + axe-core
│  ├ package.json
│  └ vite.config.ts
├ api/
│  └ openapi.yml                        バックエンドから生成（コミットする）
├ collector/                            収集ランナー（計測スクリプト・計測プロファイル・合格ライン・ツールの版）
├ .github/workflows/                    collect.yml・collect-target.yml（収集ランナー）/ ci.yml（PR の CI）
├ docs/
├ compose.yaml
└ Dockerfile                            アプリのイメージ（SPA を同梱した jar）
```

---

## 2. バックエンド

| 分類 | 採用技術 | 版 | 備考 |
| --- | --- | --- | --- |
| 言語 | Java | **25 LTS** | Temurin 25 で動作を確認している |
| フレームワーク | Spring Boot | 4.1.1 | |
| ビルドツール | Maven | 3.9 以上 | Maven Wrapper（`mvnw`）をリポジトリに同梱し、開発機と CI でバージョンを揃える |
| Web 層 | Spring MVC + 仮想スレッド | — | `spring.threads.virtual.enabled=true`。取り込みは I/O 中心のため、リアクティブの複雑さを負わずに並行性を得る |
| 認証・認可 | Spring Security（OAuth2 Client: GitHub） | — | GitHub App の user-to-server フロー。サーバサイドセッション |
| 永続化 | Spring Data JPA / Hibernate | — | |
| データベース | PostgreSQL | 17 | |
| スキーマ管理 | Flyway | — | すべてのスキーマ変更をマイグレーションで管理 |
| API 仕様 | springdoc-openapi | 3.1.1 | `openapi.yml` の生成元（4 章）。Spring Boot 4 には 3.x が対応する |
| 可観測性 | Spring Boot Actuator + Micrometer | — | Prometheus 形式でメトリクス公開 |
| JSON / XML | Jackson / StAX | — | XML は XXE を無効化して読む（2.2） |
| スケジューラ | Spring `@Scheduled` | — | 単一プロセス構成のため ShedLock は不要 |
| JSON | **Jackson 3**（`tools.jackson`） | 3.1.5 | Spring Boot 4 の既定。パッケージが `com.fasterxml` から変わる（10.1） |
| マイグレーション実行 | `spring-boot-starter-flyway` | 4.1.1 | `flyway-core` だけでは自動設定が効かない（10.1） |

### 2.1 テストと品質ツール

| 用途 | 採用技術 | 備考 |
| --- | --- | --- |
| 単体・結合テスト | JUnit 5、AssertJ、Mockito、Spring Boot Test | |
| 外部依存を含む結合テスト | Testcontainers 2.0.5（PostgreSQL） | 実際の PostgreSQL に対してテストする。H2 等の代替 DB は使わない |
| カバレッジ | JaCoCo 0.8.15 | M-01 の計測元 |
| ミューテーション | PIT（pitest）1.20.4 | M-02 の計測元。backend のみ。`-P mutation` で有効化 |
| 循環的複雑度 | maven-pmd-plugin 3.28.0（`CyclomaticComplexity`、`reportLevel: 1`） | M-07 の計測元。全関数の CC 値を出力させる |

### 2.2 成果物パーサの実装方針

取り込む成果物は外部から与えられるファイルであり、**信頼できない入力**として扱う。

| 形式 | 実装 |
| --- | --- |
| XML（JaCoCo、PIT、PMD） | StAX（`XMLInputFactory`）。`SUPPORT_DTD` と `IS_SUPPORTING_EXTERNAL_ENTITIES` を **false** に設定し、XXE と外部 DTD 参照を無効化する |
| JSON（SARIF、k6、axe-core、oasdiff） | Jackson。深さとサイズの上限を設定し、巨大・深いネストによる枯渇を防ぐ |
| lcov.info | 自前パーサ（行指向の単純な形式のため） |

いずれもストリーミング処理を基本とし、ファイル全体をメモリに展開しない。
1 ファイル 50MB の上限（FR-03-6）はあくまで最終防衛線であり、
実装側でも逐次処理を前提とする。

---

## 3. フロントエンド

| 分類 | 採用技術 | 版 | 備考 |
| --- | --- | --- | --- |
| フレームワーク | Vue | 3.5 | Composition API + `<script setup>` |
| 言語 | TypeScript | 5.x | `strict: true` + `noUncheckedIndexedAccess` |
| ビルド | Vite | 8.x | |
| ランタイム | Node.js | 24 LTS | `.nvmrc` で固定。Maven ビルドでも同じ版を取得する |
| UI コンポーネント | PrimeVue + `@primevue/themes` | 4.5.x | アクセシビリティ対応が要件（NFR 10.6）のため選定。5.x はライセンス条件が変わったため 4.5 系を採る |
| 状態管理 | Pinia | 4.x | |
| ルーティング | Vue Router | 5.x | |
| API 型・呼び出し | openapi-typescript + openapi-fetch | — | 4 章 |
| グラフ | インライン SVG（`TrendChart.vue`） | — | 3.1 |
| 単体テスト | Vitest 5 + @vue/test-utils | — | カバレッジは `@vitest/coverage-v8`。`coverage.include` を指定し、未テストのファイルも分母に含める |
| E2E / a11y | Playwright + `@axe-core/playwright` | — | M-09 の計測元 |
| Lint | ESLint（`eslint-plugin-vue`）+ Prettier | — | quality-gate 自身の静的検査。対象の M-07 は、収集ランナーが版を固定した ESLint の設定（`collector/complexity`）で測る（[02](02-metrics-spec.md) M-07） |

### 3.1 グラフとアクセシビリティ

**トレンドグラフは canvas のライブラリ（Chart.js など）ではなくインライン SVG で描く。**

canvas は**描画内容がスクリーンリーダーから読めない**ため、読み上げのための回避策
（表形式の代替表現と `role="img"` + 要約 `aria-label`）が必須になる。
SVG で描けば、その回避策そのものが要らない。

| 観点 | canvas（Chart.js） | インライン SVG |
| --- | --- | --- |
| 読み上げ | 不可。代替表現が必須 | 要素に直接ラベルを付けられる |
| 配色トークン | JS で色を解決し、テーマ切替時に再描画が要る | `var(--series-1)` がそのまま効く |
| ダークモード | 再描画が必要 | CSS だけで追従する |
| 依存 | ライブラリ 1 つ | なし |
| テスト | canvas の中身は検証できない | DOM として検証でき、axe も中を見られる |

系列数が 1〜3 本・点が数十個という本アプリのデータ密度では、ライブラリが
提供する機能（ズーム、大量点の間引き、複合軸）を必要としない。

**表形式の代替表現も併設する。** 読み上げのためではなく、
値をそのまま読みたい・コピーしたいという要求に応えるためである。

データ密度が上がって SVG の手書きが割に合わなくなった場合は ECharts への
差し替えを検討する。その際もグラフを描画する層を 1 箇所（`TrendChart.vue`）に
閉じ込めてあるため、差し替えは局所で済む。

---

## 4. バックエンドとフロントエンドの連携（OpenAPI）

**バックエンドを唯一の真実**とし、そこから `openapi.yml` を生成、
フロントエンドはそれを入力に型と API 呼び出しを生成する（コードファースト）。

```
Spring Boot のコントローラ・DTO
        │  springdoc が解析
        ▼
   api/openapi.yml            ← リポジトリにコミットする
        │  openapi-typescript
        ▼
frontend/src/api/schema.d.ts  ← リポジトリにコミットする
        │  openapi-fetch が型として利用
        ▼
   型安全な API 呼び出し
```

### 4.1 openapi.yml の生成方法

springdoc は実行中のアプリから `/v3/api-docs.yaml` を提供する。
これをファイルに書き出す方法として、**Testcontainers を使う結合テストから書き出す**方式を採る。

```java
// backend/src/test/java/.../OpenApiExportIT.java（概念）
@SpringBootTest(webEnvironment = RANDOM_PORT)
class OpenApiExportIT {
    @Test
    void exportOpenApiSpec() {
        String yaml = restClient.get().uri("/v3/api-docs.yaml").retrieve().body(String.class);
        Files.writeString(Path.of("../api/openapi.yml"), yaml);
    }
}
```

`springdoc-openapi-maven-plugin` を使う方式もあるが、プラグインはアプリを起動するために
データベース接続を必要とし、そのための専用プロファイルやモック設定が要る。
**Testcontainers は既に導入済み**であり、実際の依存を揃えてアプリを起動できるため、
専用の迂回路を用意せずに済む。

### 4.2 フロントエンドのコード生成

```jsonc
// frontend/package.json（抜粋）
{
  "scripts": {
    "generate:api": "openapi-typescript ../api/openapi.yml -o src/api/schema.d.ts && prettier --write src/api/schema.d.ts"
  }
}
```

```ts
// frontend/src/api/client.ts
import createClient from "openapi-fetch";
import type { paths } from "./schema";

export const api = createClient<paths>({ baseUrl: "/", credentials: "same-origin" });
```

呼び出し側では、パス・パラメータ・レスポンスがすべて型で保証される。

```ts
const { data, error } = await api.GET("/api/v1/runs/{runId}", {
  params: { path: { runId } },   // 型が違えばコンパイルエラー
});
```

型定義のみを生成する方式を採ったため、生成物は型宣言ファイル 1 つに収まり、
ランタイムに持ち込まれるのは `openapi-fetch` の薄いラッパだけになる。

### 4.3 生成物の同期をどう保証するか（重要）

`openapi.yml` と `schema.d.ts` は**どちらもリポジトリにコミットする**。
そのうえで、実装と生成物がずれることを CI で検出する。

| 生成物をコミットする理由 | |
| --- | --- |
| フロントエンドのビルドが、バックエンドの起動に依存しなくなる | `npm run build` 単体で完結する |
| M-08（OpenAPI 破壊的変更の検出）のベース比較が容易になる | `git show <base>:api/openapi.yml` で過去の仕様を取り出せる |
| 仕様変更がコードレビューの差分に現れる | API の変更が人の目に触れる |

コミットする以上、**更新し忘れが起きうる**。これを CI で機械的に潰す。

```bash
# CI の検証ステップ（概念）
./mvnw verify                     # openapi.yml を再生成
cd frontend && npm run generate:api   # schema.d.ts を再生成
git diff --exit-code api/ frontend/src/api/schema.d.ts
#   差分が出たら失敗 = 「実装を変えたのに生成物を更新していない」
```

生成物がずれている状態は、フロントエンドが古い契約に基づいて動いていることを意味する。
CI ではこの検証が失敗するとジョブを失敗させる（指標の成果物には含めず、ジョブの失敗として扱う）。

### 4.4 開発時の同一オリジン

開発中は Vite の dev server（5173）と Spring Boot（8080）が別ポートになるが、
**Vite のプロキシで同一オリジンに見せる**ことで、本番と同じ前提（CORS なし、Cookie 認証）で開発できる。

```ts
// frontend/vite.config.ts（抜粋）
export default defineConfig({
  server: {
    proxy: {
      "/api":          { target: "http://localhost:8080", changeOrigin: false },
      "/oauth2":       { target: "http://localhost:8080", changeOrigin: false },
      "/login/oauth2": { target: "http://localhost:8080", changeOrigin: false },
    },
  },
});
```

`changeOrigin: false` とするのは、Cookie の `Domain` 属性と OAuth のリダイレクト先を
本番同様に扱うため。ここを本番と変えると、認証まわりだけ開発で再現できない不具合が生まれる。

**プロキシ対象は `/login` 全体ではなく `/login/oauth2` に限る。**
`/login` は SPA のログイン画面のパスでもあるため、全体をバックエンドへ送ると
開発時だけログイン画面が表示できなくなる（本番では SPA フォールバックの除外が
`login/` のため `/login` は SPA に届き、開発と本番で挙動が食い違う）。
バックエンドが必要とするのは OAuth の折り返し先 `/login/oauth2/code/github` だけである。

### 4.5 本番ビルドでの同梱

Maven のビルドに frontend のビルドを組み込み、`frontend/dist` を
`backend/target/classes/static` へ配置する。

| プラグイン | 役割 |
| --- | --- |
| `frontend-maven-plugin`（com.github.eirslett） | Node.js の取得、`npm ci`、`npm run build` の実行 |
| `maven-resources-plugin` | `frontend/dist` → `target/classes/static` へのコピー |

frontend のビルドは Maven プロファイル `frontend` にまとめ、プロパティ `skipFrontend` が
無いときに有効になるようにしている。バックエンドのみを速く回したい場合は `-DskipFrontend=true` を付ける。

---

## 5. 実行・開発環境

### 5.1 Docker Compose 構成

```yaml
# compose.yaml（概念）
services:
  db:
    image: postgres:17-alpine
    environment: [POSTGRES_DB=qualitygate, ...]
    volumes: ["pgdata:/var/lib/postgresql/data"]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U ..."] }

  app:
    build: .
    depends_on: { db: { condition: service_healthy } }
    ports: ["8080:8080"]
    volumes: ["./data/artifacts:/var/lib/quality-gate/artifacts"]
    profiles: ["full"]          # 開発時は db だけ起動する
volumes: { pgdata: }
```

開発時は `docker compose up db` で PostgreSQL のみを起動し、
アプリは IDE から、フロントエンドは `npm run dev` から起動する。
`--profile full` を付けたときだけアプリもコンテナで動く。

### 5.2 成果物ストレージ：ローカルファイルシステム

成果物は**ローカルファイルシステム（バインドマウント）**に保存する。

| | 理由 |
| --- | --- |
| 規模 | 対象 1 リポジトリ・成果物 10GB 程度（NFR 10.2）。オブジェクトストレージの必要性がない |
| 構成 | オブジェクトストレージのコンテナと、その認証情報・バケット初期化の管理が要らない |
| 可搬性 | バックアップは `data/` ディレクトリごとコピーすれば済む |

ただし、保存先を直接触るコードを散らさず、**`ArtifactStore` インタフェース**を介する。
実装を `LocalFileArtifactStore` とし、将来 S3 互換へ移す場合は実装の追加のみで済むようにする。

### 5.3 WSL2 での開発

| 項目 | 方針 |
| --- | --- |
| リポジトリの配置場所 | **WSL2 の Linux ファイルシステム側（`/home/<user>/...`）に置く** |
| Docker | Docker Desktop の WSL2 バックエンド、または WSL2 内の Docker Engine |
| Testcontainers | WSL2 内の Docker ソケットを利用する。追加設定は基本的に不要 |

> **重要**: リポジトリを `/mnt/c/...`（Windows 側のファイルシステム）に置くと、
> ファイル I/O が WSL2 との境界を越えるため著しく遅くなる。
> Maven のビルド、Vite の HMR、`node_modules` の展開がいずれも体感できるほど遅延する。
> Linux ファイルシステム側に置くことを前提とし、README にも明記する。

---

## 6. 品質ツールの自己適用

quality-gate 自身は quality-gate で計測しない（[03](03-design-decisions.md) DD-5）。
代わりに Pull Request の CI（`.github/workflows/ci.yml`）で次を実行し、失敗すればマージしない。

| 検査 | ツール | 基準 |
| --- | --- | --- |
| ユニットテスト・結合テスト | JUnit（Testcontainers）/ Vitest | すべて成功 |
| 生成物の同期検証 | `api/openapi.yml` と `frontend/src/api/schema.d.ts` の再生成 | 差分が無い |
| アクセシビリティ | Playwright + `@axe-core/playwright` | critical / serious 0 件 |
| 脆弱性 | Trivy（依存関係） | 修正版のある重大・高 0 件 |

PIT・PMD・oasdiff は自身には適用しない。自身の指標を画面で見たくなった場合は、収集ランナーの対象に登録する。

---

## 7. バージョン管理の方針

| 対象 | 方針 |
| --- | --- |
| Java | `pom.xml` の `maven.compiler.release` と CI の `setup-java` で統一 |
| Maven | Maven Wrapper（`mvnw`）をコミットし、バージョンを固定 |
| Node.js | `.nvmrc` に記載し、CI の `setup-node` が参照する |
| Java 依存 | Spring Boot の BOM に従い、BOM 外のみ明示指定。バージョンレンジは使わない |
| npm 依存 | `package-lock.json` をコミットし、CI では `npm ci` を使う |
| 依存更新 | Dependabot による更新 PR（未設定）。M-06 の脆弱性検出と連動させ、更新の必要性を数値で判断する |

---

## 8. 採用しなかった選択肢

将来スタックを見直す際に同じ検討を繰り返さないよう、採らなかった選択肢と理由を示す。

| 検討した選択肢 | 採用しなかった理由 |
| --- | --- |
| Gradle | JaCoCo・PIT・springdoc・OpenAPI 書き出しという今回必須のプラグイン連携は Maven が最も枯れている。ビルド速度の差はこの規模では問題にならない |
| Java 21 LTS | 新規開発であり、より長く使える最新 LTS を採る。PIT の対応リスクは 9 章で個別に扱う |
| nginx での分離配信 | 同一オリジンの利点（CORS 不要、Cookie 認証）は得られるが、コンテナが 1 つ増える。独立デプロイの必要性が現時点でない |
| フロント/API の別オリジン | CORS 設定とクロスサイト Cookie の設計が必要になる。得られる利点がない |
| Orval + TanStack Query | キャッシュと再取得の管理は魅力的だが、ライブラリが 1 つ増える。必要になった時点で openapi-fetch の上に載せられる |
| openapi-generator (typescript-axios) | 生成物が大きく、フロントエンドの CI に JVM を持ち込む必要がある |
| MinIO（S3 互換） | この規模ではローカルファイルシステムで足りる（5.2） |
| Spring WebFlux | 取り込みは I/O 中心だが、仮想スレッドで十分。リアクティブの学習・デバッグコストに見合わない |
| ShedLock | 単一プロセス構成のため、スケジューラの多重実行が起こらない |
| Chart.js / ECharts | 本アプリのデータ密度ではインライン SVG で足り、依存も増えない。密度が足りなくなった時点で ECharts を再検討（3.1） |

---

## 9. 技術的リスクと対応

| # | リスク | 影響 | 対応 |
| --- | --- | --- | --- |
| T-1 | **PIT が対象の Java の版に対応していない、または不具合がある** | M-02 が計測できない | Java や PIT の版を上げるときは PIT を単体で検証する。動作しない場合は、Maven Toolchains で **PIT の実行時だけ前の LTS を使う**。それでも解決しない場合、スキップの申告（[03](03-design-decisions.md) DD-8）で M-02 を `SKIP` として運用し、対応版を待つ（fail-closed を壊さずに待機できる） |
| T-2 | Java 25 に対応していないライブラリがある | ビルド不能 | 依存は Spring Boot の BOM に揃え、BOM 外の依存を最小限にする |
| T-3 | 生成物（`openapi.yml` / `schema.d.ts`）の更新漏れ | フロントが古い契約で動く | CI で再生成して差分を検出し、失敗させる（4.3） |
| T-4 | canvas のグラフがスクリーンリーダーで読めない | NFR 10.6 未達 | グラフをインライン SVG で描く（3.1）。表形式の代替表現も併設する |
| T-5 | WSL2 で `/mnt/c` 配下に配置され、開発が遅い | 開発効率の低下 | README に配置場所を明記し、セットアップ手順の最初に記載する（5.3） |
| T-6 | Testcontainers が CI 環境で起動できない | 結合テストが動かない | GitHub ホストランナーは Docker を利用できる。セルフホストランナーでは Docker の利用可否を構築時に確認する |

---

## 10. 実装上の注意

この技術スタックで実装するときに気づきにくい点と、その対応を示す。依存を上げるときの手がかりにもなる。

### 10.1 Spring Boot 4 に固有の差異

| # | 事象 | 対応 |
| --- | --- | --- |
| 1 | **JSON の既定が Jackson 3** になっている。DI されるのは `tools.jackson.databind.ObjectMapper` であり、`com.fasterxml.jackson.databind.ObjectMapper` の Bean は存在しない | `tools.jackson` を使う。書き出し・読み取りの例外は非チェック例外（`tools.jackson.core.JacksonException`）に変わっている。アノテーションは `com.fasterxml.jackson.annotation` のまま |
| 2 | **`flyway-core` を依存に入れてもマイグレーションが実行されない**。Boot 4 は自動設定がモジュール分割されており、Flyway の自動設定は別成果物にある | `org.springframework.boot:spring-boot-starter-flyway` を依存に加える |
| 3 | `TestRestTemplate` が廃止されている | 結合テストでは JDK の `HttpClient` または `RestClient` を使う |
| 4 | Testcontainers 2.x で `PostgreSQLContainer` が `org.testcontainers.postgresql` へ移動し、**非ジェネリック**になっている。BOM の成果物名も `testcontainers-postgresql` / `testcontainers-junit-jupiter` に変わっている | 型引数を付けずに使う |

2 は特に気づきにくい。**アプリは正常に起動し、テーブルを参照する処理に到達して初めて失敗する**。
そのため、全マイグレーションを空の DB に適用する結合テスト（`FlywayMigrationIT`）で検出する。

### 10.2 SPA のフォールバック

`ViewControllerRegistry` で `/**/{path}` のようなパターンを登録すると、
Spring の `PathPattern` が `{*...}` と `**` の位置を制限しているため起動時に失敗する。

`ResourceHandlerRegistry` に `PathResourceResolver` を組み合わせ、
**静的ファイルとして解決できないパスを index.html に解決し直す**方式を採る
（`SpaForwardingConfig`）。API・監視・ドキュメントのパスは対象外とし、
存在しない API に 200 と HTML を返さないようにする。

### 10.3 認可の境界

SPA を同梱する構成では、**保護すべきは API であってシェルではない**。
SPA のパスまで認証必須にすると、`/runs/xxx` を直接開いたときに画面ではなく 401 が返る。

```
/api/**          → 認証必須
/actuator/health → 公開
/actuator/**     → ADMIN のみ
それ以外         → 公開（index.html と静的リソース）
```

未ログインでもシェルは返り、`/api/v1/me` の 401 を受けてフロント側が `/login` へ誘導する。

### 10.4 複数のモジュールを組み立てる設定の置き場所

`SecurityConfig` は `auth` と `ingest` の両モジュールを組み立てるため、
共通基盤である `platform` に置くと「platform が業務モジュールを知らない」という
依存規則に違反する。こうした合成点は `com.qualitygate.config` に置く。
依存規則は ArchUnit のテスト（`ModuleDependencyTest`）で検証しているため、置き場所の誤りはテストで検出される。

### 10.5 参照 API の実装上の注意

#### springdoc は入れ子レコードのスキーマ名を単純名で付ける

別の応答に同じ名前の入れ子レコードがあると、**片方の定義がもう片方を静かに上書きする**。
たとえば `RunListResponse.Item` と `FindingListResponse.Item` があると、生成された
TypeScript の型では違反一覧の要素が Run 一覧の要素になる。仕様も型も生成でき、
中身だけが別物になるため、コンパイルエラーにもならない。

対処として入れ子レコードには応答をまたいで一意な名前を付け
（`RunSummary` / `FindingItem` など）、`OpenApiExportIT` で
**一覧応答がそれぞれ別の要素スキーマを指していること**を検証している。

#### 必須と null は宣言しないと伝わらない

springdoc の既定では `required` も `nullable` も出力されず、生成される
クライアント型は**全項目が省略可能**になる。この状態では、常に埋まっている項目にも
存在確認が要り、本当に null になりうる項目（未計測の値など）と区別がつかない。
本プロジェクトは「null は未計測 / 0 は計測して 0」を厳密に分けているため、
この区別が仕様に出ないのは致命的である。

応答 DTO には必ず返す項目に `@NotNull` を、null を返しうる項目に
`@Schema(nullable = true)` を付ける。両方付いた項目は「常に存在し、値は null でありうる」
という意味になり、生成される型も `value: number | null` となる。

#### Spring Boot 4 には `@AutoConfigureMockMvc` が同梱されていない

`spring-boot-starter-test` の依存には MockMvc の自動設定が含まれない。
`MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())` で
組み立てる。JSON の直列化まで通すテストはこの方法で書いている。

#### Playwright のブラウザは環境側のものを使えるようにする

同梱ブラウザを取得できない環境（プロキシ配下など）でも動かせるよう、
`QG_E2E_CHROMIUM` に実行ファイルのパスを渡せば `executablePath` として使う。
