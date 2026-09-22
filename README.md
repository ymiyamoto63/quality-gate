# quality-gate

指定したリポジトリの品質指標を定量的に計測・蓄積し、あらかじめ定義した合格ラインに対する
**合格 / 不合格を判定して可視化する**社内向け Web アプリケーションです。

計測そのものは対象リポジトリの CI（GitHub Actions 等）で実行し、quality-gate は
その成果物（JaCoCo / PIT / k6 / SARIF / Pact / axe-core など）を取り込んで
正規化・判定・可視化・通知を担当します。

## 対象とする品質指標

| カテゴリ | 主な指標 | 合格ライン |
| --- | --- | --- |
| 機能テスト | ブランチカバレッジ / ミューテーションスコア | 75% / 60% 以上（ミューテーションは backend のみ） |
| 性能テスト | 応答時間 p95 / スループット | 到達率 50 req/s の負荷条件下で p95 500ms 以内 |
| セキュリティ | 重大・高 脆弱性件数 | 0 件 |
| コード構造 | 循環的複雑度 15 超の新規関数数 | 0 件 |
| 契約・互換性 | API 契約テスト成功率 | 100% |
| 使いやすさ | アクセシビリティ違反 | 重大 0 件 |

計測対象リポジトリの想定構成は **Java Spring Boot 4（バックエンド）+ Vue 3 SPA（フロントエンド）** です。

## 技術スタック

| レイヤ | 採用技術 |
| --- | --- |
| バックエンド | Java 25 LTS / Spring Boot 4 / Maven / Spring Data JPA / Flyway / PostgreSQL 17 |
| フロントエンド | Vue 3 / TypeScript / Vite / PrimeVue / Pinia / Vue Router |
| 連携 | バックエンドが `api/openapi.yml` を生成し、フロントが openapi-typescript + openapi-fetch で型と呼び出しを生成 |
| 配信 | SPA を Spring Boot に同梱し、同一オリジンで配信（CORS 不要 / セッション Cookie 認証） |
| テスト | JUnit 5 / Testcontainers / JaCoCo / PIT / Vitest / Playwright + axe-core |
| 実行環境 | WSL2 + Docker Compose |

詳細と選定理由は [docs/04-tech-stack.md](docs/04-tech-stack.md) を参照してください。

## ドキュメント

| ドキュメント | 内容 |
| --- | --- |
| [docs/01-requirements.md](docs/01-requirements.md) | 要件定義書 v1.1（背景・スコープ・機能要件・非機能要件・アーキテクチャ・ロードマップ） |
| [docs/02-metrics-spec.md](docs/02-metrics-spec.md) | 指標・判定仕様 v1.0（全 10 指標の定義・計算式・入力形式・境界条件） |
| [docs/03-open-questions.md](docs/03-open-questions.md) | 決定事項の記録（D-1〜D-14）と、Phase 1 と並行して確定する残課題 |
| [docs/04-tech-stack.md](docs/04-tech-stack.md) | 技術スタック v1.0（構成・OpenAPI 連携・開発環境・採用しなかった選択肢） |
| [docs/05-architecture.md](docs/05-architecture.md) | 方式設計（状態遷移・ジョブ・正規化・判定・認証認可・エラー処理） |
| [docs/06-database-design.md](docs/06-database-design.md) | データベース設計（テーブル定義・インデックス・保持期間・Flyway 規約） |
| [docs/07-api-design.md](docs/07-api-design.md) | API 設計（エンドポイント・認可マトリクス・エラーコード） |
| [docs/08-screen-design.md](docs/08-screen-design.md) | 画面設計（画面遷移・ステータス表現・各画面・アクセシビリティ） |

## ステータス

**Run 詳細・違反一覧・トレンドと M-02 ミューテーションスコアまで完了（2026-09-22）。**

| フェーズ | 状態 |
| --- | --- |
| 要件定義 | 完了（v1.1 確定） |
| 技術スタック | 完了（v1.1 確定、雛形で検証済み） |
| 基本設計（方式・DB・API・画面） | 完了 |
| プロジェクト雛形 | 完了（ビルド・テスト・起動を確認済み） |
| 正規化・判定エンジン | 完了（M-01 / M-02 / M-06 / M-07 / M-10 の 5 指標） |
| 設定解決（`.quality-gate.yml`） | 完了（検証・版管理・Run への紐づけ） |
| 参照 API と画面（S-03 Run 詳細 / S-04 違反一覧 / S-05 トレンド） | 完了 |
| 残りの指標・ユースケース・画面 | **未実装** |

### 動くもの

- Flyway による全スキーマ（V001〜V010）の適用
- Ingest API（Run 作成 / 成果物アップロード / 確定 / 状態取得）とトークン認証
- GitHub OAuth ログインと許可リストによる入口制御
- ジョブキュー（DB ベース、`FOR UPDATE SKIP LOCKED`）
- **取り込み → 正規化 → 判定 → 読み取りモデル更新**の一連の流れ
  - M-01 ブランチカバレッジ（JaCoCo XML / lcov）
  - M-02 ミューテーションスコア（PIT `mutations.xml`）— status を数え直して仕様の式で計算し、
    実行範囲（変更範囲 / 全量）ごとに前回比とトレンドの系列を分ける。
    frontend は「未計測」ではなく「対象外」と表示する
  - M-06 重大・高 脆弱性件数（SARIF）
  - M-07 循環的複雑度 15 超の新規関数数（PMD XML）
  - M-10 アクセシビリティ違反（axe-core の結果 JSON）— WCAG 2.2 AA の基準に含まれるルールの
    critical + serious を数え、設定の `pages` の画面がすべて検査されているかを確かめる。
    Run 詳細には「重大 0 件は適合の十分条件ではない」と常に注記する
  - 差分算出（新規 / 継続 / 解消 / 初回）、スキップ申告、fail-closed
- **設定解決** — CI が送る `.quality-gate.yml` を行番号つきで検証し、
  内容ハッシュで版管理して Run に紐づける
- **Run 詳細（S-03）と違反一覧（S-04）** — 判定結果をカテゴリ別に読み、
  違反を状態・深刻度で絞り込み、GitHub の該当箇所へ辿れる
  - `GET /api/v1/runs/{runId}` / `GET /api/v1/runs/{runId}/findings` / `GET /api/v1/runs`
  - 合格ライン・前回比・判定理由・スキップ申告の表示
  - 処理失敗（`FAILED`）は判定結果 `FAIL` と区別し、対処方法を添えて表示
- **トレンド（S-05）** — 指標の時系列をコンポーネント / 計測環境ごとの系列で表示
  - `GET /api/v1/repositories/{id}/trends`
  - 未計測は線を途切れさせて描き、0 を打たない（FR-08-6）
  - しきい値を重畳表示し、期間内で変わっていればその旨を示す
  - グラフはインライン SVG（canvas ではないため中身を読み上げられる）
- ダッシュボード API（`/api/v1/dashboard`）と `/api/v1/me`
- SPA を同梱した同一オリジン配信
- OpenAPI の生成 → フロントエンドの型生成（必須項目と null 許容まで宣言）
- アクセシビリティの自動検査（axe-core、ライト / ダーク両モード）。結果は M-10 の成果物として書き出す

### 未実装のもの

- **残る指標** — M-03〜05 性能 / M-08・M-09 契約
- 免除・通知・監査ログ・再評価の各ユースケース
- 残る画面 — リポジトリ詳細 / 設定 / 免除管理 / 利用者管理（ルーティングと仮画面のみ）

## 開発の始め方

前提: JDK 25 / Node.js 24 / Docker。
**WSL2 で作業する場合、リポジトリは Linux ファイルシステム側（`/home/...`）に置いてください。**
`/mnt/c` 配下はファイル I/O が遅く、ビルドと HMR が体感できるほど遅延します。

**アプリを動かすだけなら、手順 1〜3 で足ります。** ブラウザで `http://localhost:8080` を開いてください。
`spring-boot:run` がフロントエンドのビルドと同梱まで自動で行うため（Node.js も Maven が
`backend/target/` に取得します）、`npm run dev` は不要です。

```bash
# 1. データベースを起動する
docker compose up -d db

# 2. バックエンドをビルド・テストする（Testcontainers が PostgreSQL を起動します。動かすだけなら省略可）
cd backend && ./mvnw verify

# 3. アプリを起動する（GitHub App の設定と .env が必要。次節を参照）
#    画面も API も http://localhost:8080 で配信されます
./mvnw spring-boot:run
```

**画面（`frontend/`）を開発するときは**、手順 3 の代わりに次の 2 つを別々のターミナルで起動し、
`http://localhost:5173` を開きます。コードを保存するとブラウザに即時反映（HMR）されます。
8080 だけで開発すると、画面を直すたびに `spring-boot:run` を止めて再実行する必要があります。

```bash
# 3'. バックエンドを起動する（フロントエンドのビルドを飛ばして起動を速くする）
cd backend && ./mvnw spring-boot:run -DskipFrontend=true

# 4. 別ターミナルでフロントエンドの dev server を起動する（/api などは 8080 にプロキシされます）
cd frontend && npm ci && npm run dev
```

`-DskipFrontend=true` で起動したバックエンドは画面を同梱しないため、
この場合 `http://localhost:8080` を開いても画面は表示されません（API のみ）。

### 各コマンドの説明

| コマンド | 実行場所 | 何をするか |
| --- | --- | --- |
| `docker compose up -d db` | リポジトリ直下 | `compose.yaml` の `db` サービス（PostgreSQL 17）だけをバックグラウンドで起動する。DB 名・ユーザー・パスワードはいずれも `qualitygate`、ポートは `5432`。データは名前付きボリューム `pgdata` に残るため、コンテナを作り直しても消えない（消すときは `docker compose down -v`）。`app` サービスは `full` プロファイルに属しているため、ここでは起動しない |
| `./mvnw verify` | `backend/` | バックエンドのコンパイル → 単体テスト（Surefire）→ jar 作成 → 結合テスト（Failsafe、`*IT`）までを行う。結合テストは **Testcontainers が専用の PostgreSQL コンテナを別途起動する**ため、手順 1 の DB は使わない（Docker さえ動いていればよい）。あわせて JaCoCo / PMD のレポート、`api/openapi.yml`、`frontend/e2e/fixtures/*.json` を生成する。`frontend` プロファイルが有効なので、フロントエンドのビルドと同梱（後述）も行われる |
| `./mvnw spring-boot:run` | `backend/` | アプリケーションを `http://localhost:8080` で起動する。起動時に Flyway が `db/migration` の V001〜 を DB に適用し、ジョブワーカー（`JobWorker`、1 秒間隔のポーリング）も同じプロセス内で動き出す。このコマンドもコンパイルまでのフェーズを実行するため、`-DskipFrontend=true` を付けない限りフロントエンドを再ビルドして同梱する |
| `npm ci` | `frontend/` | `package-lock.json` どおりに依存関係を入れ直す（`npm install` と違い lock ファイルを書き換えない） |
| `npm run dev` | `frontend/` | Vite の dev server を `http://localhost:5173` で起動する。`.vue` / `.ts` の変更はブラウザに即時反映（HMR）される。API などは 8080 のバックエンドへプロキシされる（後述） |

その他によく使うコマンド:

| コマンド | 実行場所 | 何をするか |
| --- | --- | --- |
| `./mvnw test` | `backend/` | 単体テストのみ（Docker 不要） |
| `./mvnw -P mutation test` | `backend/` | PIT によるミューテーションテスト（M-02 の計測元）。時間がかかる |
| `./mvnw package -DskipTests` | `backend/` | フロントエンドを同梱した実行可能 jar（`target/quality-gate.jar`）を作る。`java -jar target/quality-gate.jar` で起動できる |
| `npm run build` | `frontend/` | 型検査（`vue-tsc`）のあと `frontend/dist/` に本番ビルドを出力する |
| `npm run typecheck` / `npm run lint` / `npm run format` | `frontend/` | 型検査 / ESLint（警告 0 件が条件）/ Prettier による整形 |
| `npm test` / `npm run test:coverage` | `frontend/` | Vitest による単体テスト / カバレッジつき（`reports/frontend-coverage/` に出力） |
| `npm run test:a11y` | `frontend/` | Playwright + axe-core によるアクセシビリティ検査（dev server は未起動なら自動で立ち上がる）。結果を `reports/axe-results.json`（M-10 の成果物）に書き出す |
| `docker compose --profile full up --build` | リポジトリ直下 | DB とアプリの両方をコンテナで起動する（後述の「起動の仕組み」を参照） |

### ログイン用の GitHub App

ログインは GitHub App の user-to-server 認可フローで行います（D-11）。
ローカルで動かすには、開発者ごとに GitHub App を 1 つ作成し、その認証情報を
バックエンドに渡す必要があります。

1. GitHub の **Settings → Developer settings → GitHub Apps → New GitHub App** で作成する

   | 項目 | 値 |
   | --- | --- |
   | GitHub App name | 任意（GitHub 全体で一意。例: `quality-gate-local-<GitHub ログイン名>`） |
   | Homepage URL | `http://localhost:5173` |
   | Callback URL | `http://localhost:8080/login/oauth2/code/github` と `http://localhost:5173/login/oauth2/code/github` の両方（8080 だけで動かすなら前者のみでよい） |
   | Webhook の Active | チェックを外す |
   | Repository permissions | Contents: Read-only（リポジトリ読み取り用。ログインだけなら不要） |
   | Where can this GitHub App be installed? | Only on this account |

   Callback URL は、ブラウザで開いたオリジン（8080 で直接開くか、5173 の dev server 経由か）
   によって決まる `redirect_uri` と完全一致している必要があるため、両方を登録します。

2. 作成後の画面で **Client ID** を控え、**Generate a new client secret** でシークレットを発行する
   （シークレットは発行時にしか表示されません）

3. リポジトリ直下の `.env` に書く

   ```bash
   cp .env.example .env
   # .env を編集する
   QG_GITHUB_CLIENT_ID=Iv23li...
   QG_GITHUB_CLIENT_SECRET=...
   ```

   バックエンドは起動時に `.env` を設定ファイルとして読み込みます（`application.yml` の
   `spring.config.import`）。`backend/` から起動しても、リポジトリ直下から起動しても見つかります。
   `.env` は `.gitignore` 済みです。認証情報はコミットしないでください。

   - 値は `KEY=value` の形で書き、引用符で囲まないでください（引用符も値の一部として読まれます）
   - 同じ名前の環境変数が設定されている場合は、環境変数のほうが優先されます
   - Windows 側のエディタで編集した場合は改行コードを LF にしてください
     （CRLF だと値の末尾に `\r` が付きます）

利用者が 1 件も存在しない初期状態では、**最初にログインしたユーザーが自動的に ADMIN として登録されます**。
2 人目以降は、ADMIN が許可リストに追加するまでログインできません（`/forbidden` に遷移します）。

### 起動時のよくある症状

| 症状 | 原因と対処 |
| --- | --- |
| ヘッダーだけ表示され本文が空のまま。dev server に `http proxy error: /api/v1/me` / `connect ETIMEDOUT 127.0.0.1:8080` | バックエンドに到達できていない。バックエンドが起動しているか確認する。WSL2 では Vite とバックエンドを**同じ環境**（両方 WSL 内、または両方 Windows 側）で動かす。Windows 側の IDE でバックエンドを動かす場合は `.wslconfig` に `networkingMode=mirrored` を設定する |
| 「GitHub でログイン」を押すと GitHub の 404 になり、URL に `client_id=placeholder-client-id` が含まれる | `QG_GITHUB_CLIENT_ID` / `QG_GITHUB_CLIENT_SECRET` が読み込まれていない。上記の GitHub App を作成し、リポジトリ直下の `.env` に書いてバックエンドを再起動する |
| バックエンドの起動時に `Client id of registration 'github' must not be empty` で失敗する | `.env` に `QG_GITHUB_CLIENT_ID=` のように空の値が残っている。空の値は既定値より優先されるため、値を入れるか行ごと削除する |
| GitHub で `redirect_uri is not associated with this application` と表示される | GitHub App の Callback URL が、URL 中の `redirect_uri` と一致していない。表示された `redirect_uri` をそのまま Callback URL に追加する |

疎通は Vite を動かしているのと同じ端末から `curl http://127.0.0.1:8080/actuator/health` で確認できます。

### API の型生成

バックエンドが `api/openapi.yml` を生成し、フロントエンドがそこから型を生成します。
**両方ともリポジトリにコミットします。**

```bash
cd backend && ./mvnw verify          # api/openapi.yml を再生成
cd ../frontend && npm run generate:api   # src/api/schema.d.ts を再生成（prettier で整形まで行う）
git diff --exit-code api/ frontend/src/api/schema.d.ts   # ずれていないか検証
```

この差分検証は CI でも実行します。生成物がずれている状態は、
フロントエンドが古い契約に基づいて動いていることを意味します。

`frontend/e2e/fixtures/` の応答例も同じ扱いの生成物です。
結合テスト（`RunQueryApiIT` / `TrendApiIT`）が実物の API から書き出し、
アクセシビリティ検査がそれを読んで画面を描きます。

書き出す際、UUID と処理時刻は固定値へ置き換えます（`FixtureWriter`）。
そのままだと実行のたびに差分が出て、生成物なのに
「再生成して差分がないこと」を検証できなくなるためです。

### アクセシビリティ検査（M-10）

```bash
cd frontend && npx playwright test      # ライト / ダークの両モードで検査
```

dev server は起動していなければ自動で立ち上がります（起動済みならそれを使います）。
検査先を変える場合は `QG_E2E_BASE_URL` を、同梱ブラウザを取得できない環境では
`QG_E2E_CHROMIUM` に Chromium の実行ファイルのパスを渡してください。

axe-core の結果は `reports/axe-results.json` に書き出され、これを `axe-json` として
quality-gate に送ります。同じ場所の `playwright-results.json` はテストレポートで、
axe の結果ではありません。検査する画面を足したら `.quality-gate.yml` の
`accessibility.pages` にも足してください。

## 起動の仕組み

### 全体像

```
                    ┌──────────────────────── ブラウザ ────────────────────────┐
                    │  開発時: http://localhost:5173   /  同梱時: :8080       │
                    └───────────────┬──────────────────────────┬──────────────┘
                                    │ 画面（SPA）               │ /api, /oauth2, /login/oauth2, ...
                                    ▼                          ▼
             ┌─────────────────────────────┐   proxy   ┌───────────────────────────────┐
  開発時のみ │ Vite dev server (:5173)     │ ────────▶ │ Spring Boot (:8080)           │
             │  frontend/src を HMR で配信  │           │  ├ REST API (/api/v1/**)      │
             └─────────────────────────────┘           │  ├ OAuth ログイン（GitHub App）│
                                                       │  ├ 同梱 SPA (classpath:/static)│
   CI（GitHub Actions）── Ingest API ────────────────▶ │  └ JobWorker（判定ジョブ）     │
    Bearer qg_xxx_yyy    POST /api/v1/runs/...         └──────┬──────────────┬─────────┘
                                                              │ JDBC         │ ファイル
                                                              ▼              ▼
                                                     PostgreSQL 17     data/artifacts/
                                                     (:5432, Flyway)   （成果物ストア）
                                                              ▲
                              GitHub ◀── OAuth（user-to-server 認可）── Spring Boot
```

プロセスとして必要なのは **PostgreSQL と Spring Boot の 2 つだけ**です。
Vite の dev server は開発の利便性（HMR）のためのもので、本番には存在しません。
ジョブキューも DB 上に実装しているため、Redis やメッセージブローカーは不要です。

### 起動方法の 3 パターン

| パターン | 起動するもの | ブラウザで開く URL | 用途 |
| --- | --- | --- | --- |
| A. 分離起動（通常の開発） | `db` コンテナ / `./mvnw spring-boot:run -DskipFrontend=true` / `npm run dev` | `http://localhost:5173` | 画面を触りながら開発する。フロントの変更は即時反映、バックエンドの変更は再起動で反映 |
| B. 同梱起動 | `db` コンテナ / `./mvnw spring-boot:run` | `http://localhost:8080` | 本番に近い形での動作確認。SPA はビルド時点のものが配信されるため、フロントを直したら再ビルドが必要 |
| C. すべてコンテナ | `docker compose --profile full up --build` | `http://localhost:8080` | JDK / Node.js を入れずに動かす、またはデモ用 |

パターン C では `Dockerfile` がマルチステージビルドで jar を作り（ビルドステージで Maven が
Node.js を取得してフロントもビルドする）、JRE だけの実行イメージで起動します。
DB の接続先は `compose.yaml` で `db:5432` に差し替えられ、`app` は `db` のヘルスチェックが
通ってから起動します。GitHub App の認証情報は、Docker Compose がリポジトリ直下の `.env` を
変数展開に使うことで `QG_GITHUB_CLIENT_ID` / `QG_GITHUB_CLIENT_SECRET` として渡されます。
成果物は `./data/artifacts` にマウントされます。

### フロントエンドとバックエンドの連携

**同一オリジンで動かすことが前提**です（CORS 設定なし、セッション Cookie 認証）。
開発時と本番時で、同一オリジンを実現する方法が異なります。

- **本番・同梱時（パターン B / C）**
  1. Maven の `frontend` プロファイル（`-DskipFrontend` を付けない限り有効）が
     `frontend-maven-plugin` で Node.js を `backend/target/` に取得し、`npm ci` → `npm run build` を実行する
  2. 出力された `frontend/dist/` を `maven-resources-plugin` が `target/classes/static/` にコピーし、jar に含める
  3. Spring Boot が `classpath:/static/` から SPA を配信する。`/runs/xxx` のように
     静的ファイルとして存在しないパスは `SpaForwardingConfig` が `index.html` を返し、
     ルーティングを Vue Router に任せる。ただし `api/` `actuator/` `oauth2/` `login/` `logout` などは
     対象外で、存在しない API には正しく 404 を返す

- **開発時（パターン A）**
  - ブラウザは 5173（Vite）だけを見る。`vite.config.ts` の `server.proxy` で、次のパスを 8080 に転送する

    | パス | 用途 |
    | --- | --- |
    | `/api` | REST API |
    | `/oauth2` | ログイン開始（`/oauth2/authorization/github`） |
    | `/login/oauth2` | GitHub からの折り返し（`/login/oauth2/code/github`）。`/login` 全体ではない点に注意（SPA のログイン画面 `/login` は Vite が返す） |
    | `/logout` | ログアウト |
    | `/badges` | バッジ |
    | `/actuator` | ヘルスチェックなど |

  - `changeOrigin: false` にしているため、バックエンドから見ても Host は `localhost:5173` のままで、
    セッション Cookie もブラウザには 5173 のものとして保存される。
    本番と同じ「同一オリジン」の条件で認証まわりを確認できる

- **API の型の共有** — バックエンドの springdoc が OpenAPI を生成し（`OpenApiExportIT` が
  `api/openapi.yml` に書き出す）、フロントエンドは `npm run generate:api` で
  `src/api/schema.d.ts` を生成する。画面は `src/api/client.ts` の `openapi-fetch` クライアント
  （`baseUrl: '/'`, `credentials: 'same-origin'`）で呼び出すため、API の変更は型エラーとして検出される

- **画面を開いたときの流れ**
  1. SPA が読み込まれると、Vue Router のガードが `GET /api/v1/me` を呼ぶ
  2. 未ログインならバックエンドは `401` を返す（SPA のシェル自体は認証なしで配信される）。
     フロントは `/login` 画面へ遷移する
  3. ログイン済みならユーザー情報とロール（`ADMIN` / `VIEWER`）が返り、目的の画面を表示する。
     画面側のロール判定は表示の都合だけで、権限の境界はバックエンドの認可が担保する

### GitHub App との関係

quality-gate は GitHub App を **「ログイン手段」として使います**（OAuth App は使いません。D-11）。
計測データを GitHub から取りに行くことはなく、計測結果は CI が Ingest API で送ってきます。

| 用途 | 使うもの | 状態 |
| --- | --- | --- |
| 画面へのログイン | GitHub App の Client ID / Client Secret（user-to-server 認可、スコープ `read:user`） | 実装済み |
| CI からの計測結果の送信 | GitHub App ではなく **Ingest Token**（quality-gate が発行する Bearer トークン） | 実装済み（発行画面は未実装） |
| 違反箇所へのリンク | `https://github.com/<owner>/<repo>/blob/<sha>/<path>#L<n>` を組み立てるだけ（API 呼び出しなし） | 実装済み |
| リポジトリ内容の読み取り | GitHub App の Contents: Read-only 権限 | 将来用。現時点では不要 |

ログインの流れ（`SecurityConfig` の `oauth2Login`）:

1. ログイン画面の「GitHub でログイン」が `/oauth2/authorization/github` を開く
2. Spring Security が GitHub の認可画面へリダイレクトする。このとき `redirect_uri` は
   **ブラウザで開いているオリジン**から組み立てられる（5173 経由なら `http://localhost:5173/login/oauth2/code/github`）。
   そのため GitHub App の Callback URL には 8080 と 5173 の両方を登録する
3. 利用者が承認すると GitHub が `/login/oauth2/code/github` に折り返し、
   バックエンドが認可コードを Client Secret と引き換えにアクセストークンへ交換し、GitHub のユーザー情報を取得する
4. `AllowlistOAuth2UserService` が GitHub ユーザーを `users` テーブル（許可リスト）と照合する
   - 利用者が 0 件なら、最初のユーザーを `ADMIN` として登録する（初回のみ）
   - 未登録・無効化されたユーザーは拒否して `/forbidden` へ
5. 成功するとセッションを作成して `/` へリダイレクトする。セッションは Spring Session JDBC で
   **DB に保存**される（有効期限 8 時間）ため、バックエンドを再起動してもログイン状態は保たれる

GitHub アカウントを持つ人なら誰でも手順 1〜3 までは進めるため、**手順 4 の許可リストが実質的なアクセス制御**です。
Organization のメンバーシップによる制限は行っていません。

### CI からの取り込みと判定ジョブ

認証の経路は 2 つに分かれています（`SecurityConfig`）。

| 経路 | 対象 | 認証 | CSRF |
| --- | --- | --- | --- |
| Ingest | `POST /api/v1/runs/**` と `GET /api/v1/runs/{id}/status` | `Authorization: Bearer qg_<prefix>_<secret>`（リポジトリ単位の Ingest Token） | 無効（Cookie を使わない） |
| 画面 | それ以外の `/api/**` | GitHub ログインのセッション Cookie | 有効 |

取り込みから表示までの流れ:

1. CI が `POST /api/v1/runs` で Run を作成する（`repository` はトークンの発行元と一致する必要がある）
2. `POST /api/v1/runs/{runId}/artifacts` で成果物（`jacoco-xml` / `pit-xml` / `sarif` / `pmd-xml` / `quality-gate-config` など）を
   アップロードする。この時点ではパースせず、`QG_ARTIFACT_ROOT` に保存するだけ
3. `POST /api/v1/runs/{runId}/finalize` で完了を宣言すると、判定ジョブが DB のジョブキューに積まれ、すぐに `202` が返る
4. 同じプロセス内の `JobWorker` がキューを 1 秒間隔でポーリングし（`FOR UPDATE SKIP LOCKED`）、
   正規化 → 判定 → 読み取りモデル更新を行う
5. 画面（Run 詳細 / 違反一覧 / トレンド / ダッシュボード）に結果が表示される

リポジトリの登録と Ingest Token の発行画面はまだ無いため、ローカルで取り込みを試すときは
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
       \"branch\":\"main\",\"runnerType\":\"self-hosted\",\"triggeredBy\":\"local\",
       \"measuredAt\":\"$(date -u +%FT%TZ)\"}" | sed -E 's/.*"runId":"([^"]+)".*/\1/')

curl -s -X POST "http://localhost:8080/api/v1/runs/$RUN_ID/artifacts?type=quality-gate-config" \
  -H "Authorization: Bearer $TOKEN" -F file=@.quality-gate.yml
curl -s -X POST "http://localhost:8080/api/v1/runs/$RUN_ID/artifacts?type=jacoco-xml&component=backend" \
  -H "Authorization: Bearer $TOKEN" -F file=@backend/target/site/jacoco/jacoco.xml
curl -s -X POST "http://localhost:8080/api/v1/runs/$RUN_ID/finalize" -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/runs/$RUN_ID/status" -H "Authorization: Bearer $TOKEN"
```

リクエスト・応答の詳細は `http://localhost:8080/swagger-ui.html` または
[docs/07-api-design.md](docs/07-api-design.md) を参照してください。

### 設定値（環境変数 / `.env`）

| 変数 | 既定値 | 説明 |
| --- | --- | --- |
| `QG_GITHUB_CLIENT_ID` / `QG_GITHUB_CLIENT_SECRET` | ダミー値 | ログイン用 GitHub App の認証情報。未設定でも起動はするが、ログインできない |
| `QG_DB_URL` / `QG_DB_USERNAME` / `QG_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/qualitygate` / `qualitygate` / `qualitygate` | 接続先 DB。`compose.yaml` の `db` と一致している |
| `QG_ARTIFACT_ROOT` | `./data/artifacts` | 成果物の保存先（起動したディレクトリからの相対パス） |
| `QG_BASE_URL` | `http://localhost:8080` | 取り込み API の応答に含める Run 詳細画面の URL の組み立てに使う |
| `QG_SLACK_WEBHOOK_URL` | なし | 通知用（通知機能は未実装） |

優先順位は **環境変数 > `.env` > `application.yml` の既定値**です。

## ディレクトリ構成

```
quality-gate/
├ backend/          Spring Boot 4（Maven）
│  └ src/main/resources/db/migration/   Flyway マイグレーション
├ frontend/         Vue 3 + Vite
│  └ src/api/schema.d.ts                openapi.yml から生成（コミットする）
├ api/openapi.yml   バックエンドから生成（コミットする）
├ docs/             要件定義・設計ドキュメント
├ compose.yaml      PostgreSQL（+ プロファイル full でアプリ）
└ .quality-gate.yml 自分自身の品質ゲート設定
```
