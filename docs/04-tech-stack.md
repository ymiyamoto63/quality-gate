# quality-gate 技術スタック

| 項目 | 内容 |
| --- | --- |
| ドキュメント名 | quality-gate 技術スタック |
| バージョン | **1.0（確定）** |
| 最終更新 | 2026-09-21 |
| ステータス | **確定**。[要件定義書](01-requirements.md) v1.0 の付属仕様 |

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
├ docs/
├ compose.yaml
└ .quality-gate.yml                     自分自身の品質ゲート設定
```

---

## 2. バックエンド

| 分類 | 採用技術 | 版 | 備考 |
| --- | --- | --- | --- |
| 言語 | Java | **25 LTS** | |
| フレームワーク | Spring Boot | 4.x | |
| ビルドツール | Maven | 3.9 以上 | Maven Wrapper（`mvnw`）をリポジトリに同梱し、開発機と CI でバージョンを揃える |
| Web 層 | Spring MVC + 仮想スレッド | — | `spring.threads.virtual.enabled=true`。取り込みは I/O 中心のため、リアクティブの複雑さを負わずに並行性を得る |
| 認証・認可 | Spring Security（OAuth2 Client: GitHub） | — | GitHub App の user-to-server フロー。サーバサイドセッション |
| 永続化 | Spring Data JPA / Hibernate | — | |
| データベース | PostgreSQL | 17 | |
| スキーマ管理 | Flyway | — | すべてのスキーマ変更をマイグレーションで管理 |
| API 仕様 | springdoc-openapi | 2.x | `openapi.yml` の生成元（4 章） |
| 可観測性 | Spring Boot Actuator + Micrometer | — | Prometheus 形式でメトリクス公開 |
| JSON / XML | Jackson / StAX | — | XML は XXE を無効化して読む（2.2） |
| スケジューラ | Spring `@Scheduled` | — | 単一プロセス構成のため ShedLock は不要（4.3 参照） |

### 2.1 テストと品質ツール

| 用途 | 採用技術 | 備考 |
| --- | --- | --- |
| 単体・結合テスト | JUnit 5、AssertJ、Mockito、Spring Boot Test | |
| 外部依存を含む結合テスト | Testcontainers（PostgreSQL） | 実際の PostgreSQL に対してテストする。H2 等の代替 DB は使わない |
| カバレッジ | JaCoCo | M-01 の計測元 |
| ミューテーション | PIT（pitest） | M-02 の計測元。backend のみ |
| 循環的複雑度 | PMD（`CyclomaticComplexity`、`reportLevel: 1`） | M-07 の計測元。全関数の CC 値を出力させる |

### 2.2 成果物パーサの実装方針

取り込む成果物は外部から与えられるファイルであり、**信頼できない入力**として扱う。

| 形式 | 実装 |
| --- | --- |
| XML（JaCoCo、PIT、PMD） | StAX（`XMLInputFactory`）。`SUPPORT_DTD` と `IS_SUPPORTING_EXTERNAL_ENTITIES` を **false** に設定し、XXE と外部 DTD 参照を無効化する |
| JSON（SARIF、k6、axe-core、oasdiff） | Jackson。深さとサイズの上限を設定し、巨大・深いネストによる枯渇を防ぐ |
| lcov.info | 自前パーサ（行指向の単純な形式のため） |

いずれもストリーミング処理を基本とし、ファイル全体をメモリに展開しない。
1 ファイル 50MB の上限（FR-03-8）はあくまで最終防衛線であり、
実装側でも逐次処理を前提とする。

---

## 3. フロントエンド

| 分類 | 採用技術 | 版 | 備考 |
| --- | --- | --- | --- |
| フレームワーク | Vue | 3.x | Composition API + `<script setup>` |
| 言語 | TypeScript | 5.x | `strict: true` |
| ビルド | Vite | 5.x 以上 | |
| ランタイム | Node.js | 24 LTS | `.nvmrc` でバージョンを固定 |
| UI コンポーネント | PrimeVue | 4.x | アクセシビリティ対応が要件（NFR 10.6）のため選定 |
| 状態管理 | Pinia | 3.x | |
| ルーティング | Vue Router | 4.x | |
| API 型・呼び出し | openapi-typescript + openapi-fetch | — | 4 章 |
| グラフ | PrimeVue `Chart`（Chart.js） | — | 3.1 の注意事項あり |
| 単体テスト | Vitest + @vue/test-utils | — | カバレッジは `@vitest/coverage-v8` |
| E2E / a11y | Playwright + `@axe-core/playwright` | — | M-10 の計測元 |
| Lint | ESLint（`eslint-plugin-vue`、`complexity` ルール）+ Prettier | — | `complexity` は M-07 の計測元 |

### 3.1 グラフとアクセシビリティ

Chart.js は canvas に描画するため、**描画内容がスクリーンリーダーから読めない**。
NFR 10.6（自身が WCAG 2.2 AA を満たす）を達成するには、グラフ単体では不十分である。

そのため、すべてのトレンドグラフに次を併設することを実装要件とする。

- グラフと同じデータを持つ**表形式の代替表現**（視覚的には折りたたんでよいが、DOM には存在させる）
- canvas 要素への `role="img"` と、傾向を要約した `aria-label`
- しきい値ラインは色だけでなく**凡例のテキスト**でも示す

データ密度が上がってグラフの表現力が足りなくなった場合は、ECharts への差し替えを検討する。
その際もグラフを描画する層を 1 箇所に閉じ込めておき、差し替えが局所で済むようにする。

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
    "generate:api": "openapi-typescript ../api/openapi.yml -o src/api/schema.d.ts"
  }
}
```

```ts
// frontend/src/api/client.ts
import createClient from "openapi-fetch";
import type { paths } from "./schema";

export const api = createClient<paths>({ baseUrl: "/api" });
```

呼び出し側では、パス・パラメータ・レスポンスがすべて型で保証される。

```ts
const { data, error } = await api.GET("/v1/runs/{runId}", {
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
| M-09（OpenAPI 破壊的変更の検出）のベース比較が容易になる | `git show <base>:api/openapi.yml` で過去の仕様を取り出せる |
| 仕様変更がコードレビューの差分に現れる | API の変更が人の目に触れる |

コミットする以上、**更新し忘れが起きうる**。これを CI で機械的に潰す。

```bash
# CI の検証ステップ（概念）
./mvnw verify                     # openapi.yml を再生成
cd frontend && npm run generate:api   # schema.d.ts を再生成
git diff --exit-code api/ frontend/src/api/schema.d.ts
#   差分が出たら失敗 = 「実装を変えたのに生成物を更新していない」
```

この検証は M-08（契約テスト成功率）の一部として扱う。
生成物がずれている状態は、フロントエンドが古い契約に基づいて動いていることを意味し、
契約テストの前提が崩れているためである。

### 4.4 開発時の同一オリジン

開発中は Vite の dev server（5173）と Spring Boot（8080）が別ポートになるが、
**Vite のプロキシで同一オリジンに見せる**ことで、本番と同じ前提（CORS なし、Cookie 認証）で開発できる。

```ts
// frontend/vite.config.ts（抜粋）
export default defineConfig({
  server: {
    proxy: {
      "/api":   { target: "http://localhost:8080", changeOrigin: false },
      "/oauth2": { target: "http://localhost:8080", changeOrigin: false },
      "/login":  { target: "http://localhost:8080", changeOrigin: false },
    },
  },
});
```

`changeOrigin: false` とするのは、Cookie の `Domain` 属性と OAuth のリダイレクト先を
本番同様に扱うため。ここを本番と変えると、認証まわりだけ開発で再現できない不具合が生まれる。

### 4.5 本番ビルドでの同梱

Maven のビルドに frontend のビルドを組み込み、`frontend/dist` を
`backend/target/classes/static` へ配置する。

| プラグイン | 役割 |
| --- | --- |
| `frontend-maven-plugin`（com.github.eirslett） | Node.js の取得、`npm ci`、`npm run build` の実行 |
| `maven-resources-plugin` | `frontend/dist` → `target/classes/static` へのコピー |

バックエンドのみを速く回したい場合のために、frontend のビルドを飛ばす
Maven プロファイル（`-P skip-frontend`）を用意する。

---

## 5. 実行・開発環境

### 5.1 Docker Compose 構成

```yaml
# compose.yaml（概念）
services:
  db:
    image: postgres:17
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

当初の想定では S3 互換ストレージ（MinIO）としていたが、
**ローカルファイルシステム（バインドマウント）に変更する**。

| | 理由 |
| --- | --- |
| 規模 | 対象 1 リポジトリ・成果物 10GB 程度（NFR 10.2）。オブジェクトストレージの必要性がない |
| 構成 | MinIO コンテナと、その認証情報・バケット初期化の管理が不要になる |
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

## 6. 品質ツールの自己適用（ドッグフーディング）

quality-gate 自身を quality-gate の計測対象とする（NFR 10.7、受け入れ基準 A-7）。
各指標を、どのツールで、どのコンポーネントに対して計測するかを以下に定める。

| 指標 | backend | frontend |
| --- | --- | --- |
| M-01 ブランチカバレッジ | JaCoCo | Vitest（`@vitest/coverage-v8`） |
| M-02 ミューテーションスコア | PIT | **対象外**（D-9） |
| M-03〜05 性能 | k6（専有ランナー） | — |
| M-06 脆弱性 | Trivy（依存・イメージ）、Semgrep（SAST）、gitleaks（シークレット） | 同左（npm 依存を含む） |
| M-07 循環的複雑度 | PMD | ESLint `complexity` |
| M-08 契約テスト成功率 | 契約テスト（JUnit）+ 4.3 の生成物同期検証 | Vitest（JUnit reporter） |
| M-09 破壊的変更 | oasdiff（`api/openapi.yml` の base/head 比較） | — |
| M-10 アクセシビリティ | — | Playwright + `@axe-core/playwright` |

---

## 7. バージョン管理の方針

| 対象 | 方針 |
| --- | --- |
| Java | `pom.xml` の `maven.compiler.release` と CI の `setup-java` で統一 |
| Maven | Maven Wrapper（`mvnw`）をコミットし、バージョンを固定 |
| Node.js | `.nvmrc` に記載し、CI の `setup-node` が参照する |
| Java 依存 | Spring Boot の BOM に従い、BOM 外のみ明示指定。バージョンレンジは使わない |
| npm 依存 | `package-lock.json` をコミットし、CI では `npm ci` を使う |
| 依存更新 | Dependabot による更新 PR。M-06 の脆弱性検出と連動させ、更新の必要性を数値で判断する |

---

## 8. 採用しなかった選択肢

判断の経緯を残す。将来スタックを見直す際、同じ検討を繰り返さないため。

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
| ECharts | PrimeVue に Chart.js ベースの `Chart` が含まれ、依存を増やさずに済む。密度が足りなくなった時点で再検討（3.1） |

---

## 9. 技術的リスクと対応

| # | リスク | 影響 | 対応 |
| --- | --- | --- | --- |
| T-1 | **PIT が Java 25 に未対応、または不具合がある** | M-02 が計測できない | 導入初日に PIT を単体で検証する。動作しない場合は、Maven Toolchains で **PIT の実行時のみ Java 21 を使う**。それでも解決しない場合、D-13 のスキップ申告により M-02 を `SKIP` として運用し、対応版を待つ（fail-closed を壊さずに待機できる） |
| T-2 | Java 25 に対応していないライブラリがある | ビルド不能 | 依存は Spring Boot の BOM に揃え、BOM 外の依存を最小限にする。初期構築時に全依存の動作を確認する |
| T-3 | 生成物（`openapi.yml` / `schema.d.ts`）の更新漏れ | フロントが古い契約で動く | CI で再生成して差分を検出し、失敗させる（4.3） |
| T-4 | Chart.js のグラフがスクリーンリーダーで読めない | NFR 10.6 未達 | すべてのグラフに表形式の代替表現を併設することを実装要件とする（3.1） |
| T-5 | WSL2 で `/mnt/c` 配下に配置され、開発が遅い | 開発効率の低下 | README に配置場所を明記し、セットアップ手順の最初に記載する（5.3） |
| T-6 | Testcontainers が CI 環境で起動できない | 結合テストが動かない | GitHub ホストランナーは Docker を利用できる。セルフホストランナーでは Docker の利用可否を構築時に確認する |
