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

**Run 詳細・違反一覧・トレンドまで完了（2026-09-22）。**

| フェーズ | 状態 |
| --- | --- |
| 要件定義 | 完了（v1.1 確定） |
| 技術スタック | 完了（v1.1 確定、雛形で検証済み） |
| 基本設計（方式・DB・API・画面） | 完了 |
| プロジェクト雛形 | 完了（ビルド・テスト・起動を確認済み） |
| 正規化・判定エンジン | 完了（M-01 / M-06 / M-07 の 3 指標） |
| 設定解決（`.quality-gate.yml`） | 完了（検証・版管理・Run への紐づけ） |
| 参照 API と画面（S-03 Run 詳細 / S-04 違反一覧 / S-05 トレンド） | 完了 |
| 残りの指標・ユースケース・画面 | **未実装** |

### 動くもの

- Flyway による全スキーマ（V001〜V009）の適用
- Ingest API（Run 作成 / 成果物アップロード / 確定 / 状態取得）とトークン認証
- GitHub OAuth ログインと許可リストによる入口制御
- ジョブキュー（DB ベース、`FOR UPDATE SKIP LOCKED`）
- **取り込み → 正規化 → 判定 → 読み取りモデル更新**の一連の流れ
  - M-01 ブランチカバレッジ（JaCoCo XML / lcov）
  - M-06 重大・高 脆弱性件数（SARIF）
  - M-07 循環的複雑度 15 超の新規関数数（PMD XML）
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
- アクセシビリティの自動検査（axe-core、ライト / ダーク両モード）

### 未実装のもの

- **残る指標** — M-02 ミューテーション / M-03〜05 性能 / M-08・M-09 契約 / M-10 アクセシビリティ
- 免除・通知・監査ログ・再評価の各ユースケース
- 残る画面 — リポジトリ詳細 / 設定 / 免除管理 / 利用者管理（ルーティングと仮画面のみ）

## 開発の始め方

前提: JDK 25 / Node.js 24 / Docker。
**WSL2 で作業する場合、リポジトリは Linux ファイルシステム側（`/home/...`）に置いてください。**
`/mnt/c` 配下はファイル I/O が遅く、ビルドと HMR が体感できるほど遅延します。

```bash
# 1. データベースを起動する
docker compose up -d db

# 2. バックエンドをビルド・テストする（Testcontainers が PostgreSQL を起動します）
cd backend && ./mvnw verify

# 3. バックエンドを起動する（GitHub App の設定が必要。次節を参照）
export QG_GITHUB_CLIENT_ID=...
export QG_GITHUB_CLIENT_SECRET=...
./mvnw spring-boot:run

# 4. 別ターミナルでフロントエンドを起動する（/api は 8080 にプロキシされます）
cd frontend && npm ci && npm run dev
```

フロントエンドだけを触るときは `-DskipFrontend=true` を付けると Maven の
フロントエンドビルドを飛ばせます。

### ログイン用の GitHub App

ログインは GitHub App の user-to-server 認可フローで行います（D-11）。
ローカルで動かすには、開発者ごとに GitHub App を 1 つ作成し、その認証情報を
バックエンドに渡す必要があります。

1. GitHub の **Settings → Developer settings → GitHub Apps → New GitHub App** で作成する

   | 項目 | 値 |
   | --- | --- |
   | GitHub App name | 任意（GitHub 全体で一意。例: `quality-gate-local-<GitHub ログイン名>`） |
   | Homepage URL | `http://localhost:5173` |
   | Callback URL | `http://localhost:8080/login/oauth2/code/github` と `http://localhost:5173/login/oauth2/code/github` の両方 |
   | Webhook の Active | チェックを外す |
   | Repository permissions | Contents: Read-only（リポジトリ読み取り用。ログインだけなら不要） |
   | Where can this GitHub App be installed? | Only on this account |

   Callback URL は、ブラウザで開いたオリジン（8080 で直接開くか、5173 の dev server 経由か）
   によって決まる `redirect_uri` と完全一致している必要があるため、両方を登録します。

2. 作成後の画面で **Client ID** を控え、**Generate a new client secret** でシークレットを発行する
   （シークレットは発行時にしか表示されません）

3. バックエンドの起動時に環境変数で渡す

   ```bash
   export QG_GITHUB_CLIENT_ID=Iv23li...
   export QG_GITHUB_CLIENT_SECRET=...
   ```

   IDE からデバッグ実行する場合は、実行構成の環境変数に同じ値を設定します。
   **Spring Boot は `.env` を自動では読み込みません。** `.env.example` を `.env` に
   コピーしただけでは反映されないため注意してください（`.env` は compose の `full` プロファイル用です）。
   認証情報はコミットしないでください。

利用者が 1 件も存在しない初期状態では、**最初にログインしたユーザーが自動的に ADMIN として登録されます**。
2 人目以降は、ADMIN が許可リストに追加するまでログインできません（`/forbidden` に遷移します）。

### 起動時のよくある症状

| 症状 | 原因と対処 |
| --- | --- |
| ヘッダーだけ表示され本文が空のまま。dev server に `http proxy error: /api/v1/me` / `connect ETIMEDOUT 127.0.0.1:8080` | バックエンドに到達できていない。バックエンドが起動しているか確認する。WSL2 では Vite とバックエンドを**同じ環境**（両方 WSL 内、または両方 Windows 側）で動かす。Windows 側の IDE でバックエンドを動かす場合は `.wslconfig` に `networkingMode=mirrored` を設定する |
| 「GitHub でログイン」を押すと GitHub の 404 になり、URL に `client_id=placeholder-client-id` が含まれる | `QG_GITHUB_CLIENT_ID` / `QG_GITHUB_CLIENT_SECRET` が未設定。上記の GitHub App を作成し、環境変数を設定してバックエンドを再起動する |
| GitHub で `redirect_uri is not associated with this application` と表示される | GitHub App の Callback URL が、URL 中の `redirect_uri` と一致していない。表示された `redirect_uri` をそのまま Callback URL に追加する |

疎通は Vite を動かしているのと同じ端末から `curl http://127.0.0.1:8080/actuator/health` で確認できます。

### API の型生成

バックエンドが `api/openapi.yml` を生成し、フロントエンドがそこから型を生成します。
**両方ともリポジトリにコミットします。**

```bash
cd backend && ./mvnw verify          # api/openapi.yml を再生成
cd ../frontend && npm run generate:api   # src/api/schema.d.ts を再生成
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
cd frontend && npm run dev              # 別ターミナルで dev server を起動
npx playwright test                     # ライト / ダークの両モードで検査
```

同梱ブラウザを取得できない環境では、`QG_E2E_CHROMIUM` に
Chromium の実行ファイルのパスを渡してください。

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
