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

**プロジェクト雛形まで完了（2026-09-21）。**

| フェーズ | 状態 |
| --- | --- |
| 要件定義 | 完了（v1.1 確定） |
| 技術スタック | 完了（v1.1 確定、雛形で検証済み） |
| 基本設計（方式・DB・API・画面） | 完了 |
| プロジェクト雛形 | 完了（ビルド・テスト・起動を確認済み） |
| 正規化・判定エンジン | 完了（M-01 / M-06 / M-07 の 3 指標） |
| 設定解決（`.quality-gate.yml`） | 完了（検証・版管理・Run への紐づけ） |
| 残りの指標・ユースケース・画面 | **未実装** |

### 動くもの

- Flyway による全スキーマ（V001〜V008）の適用
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
- ダッシュボード API（`/api/v1/dashboard`）と `/api/v1/me`
- SPA を同梱した同一オリジン配信
- OpenAPI の生成 → フロントエンドの型生成

### 未実装のもの

- **残る指標** — M-02 ミューテーション / M-03〜05 性能 / M-08・M-09 契約 / M-10 アクセシビリティ
- 免除・通知・監査ログ・トレンド・再評価の各ユースケース
- ダッシュボード以外の画面（ルーティングと仮画面のみ）

## 開発の始め方

前提: JDK 25 / Node.js 24 / Docker。
**WSL2 で作業する場合、リポジトリは Linux ファイルシステム側（`/home/...`）に置いてください。**
`/mnt/c` 配下はファイル I/O が遅く、ビルドと HMR が体感できるほど遅延します。

```bash
# 1. データベースを起動する
docker compose up -d db

# 2. バックエンドをビルド・テストする（Testcontainers が PostgreSQL を起動します）
cd backend && ./mvnw verify

# 3. バックエンドを起動する
./mvnw spring-boot:run

# 4. 別ターミナルでフロントエンドを起動する（/api は 8080 にプロキシされます）
cd frontend && npm ci && npm run dev
```

フロントエンドだけを触るときは `-DskipFrontend=true` を付けると Maven の
フロントエンドビルドを飛ばせます。

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
