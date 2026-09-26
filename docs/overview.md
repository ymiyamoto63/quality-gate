# 概要

quality-gate は、指定したリポジトリの品質指標を定量的に計測・蓄積し、あらかじめ定義した合格ラインに対する
**合格 / 不合格を判定して可視化する**社内向け Web アプリケーションです。

計測は quality-gate 側の**収集ランナー**（quality-gate リポジトリの GitHub Actions とセルフホストランナー）が
対象リポジトリを取得して行い、その成果物（JaCoCo / lcov / SARIF / PMD / JUnit XML / oasdiff など）を
Ingest API で送ります。対象リポジトリには設定ファイルもワークフローも置きません。
バックエンドは送られた成果物を取り込んで、正規化・判定・可視化を担当します（テストは実行しません）。
リリース前には、タグかコミットを指定して全指標の合否と結論（リリースしてよいか）を 1 画面で確認し、CSV を証跡として残せます（S-09）。
取り込み経路は収集ランナーだけです。quality-gate 自身は計測せず、Pull Request の CI でテストと検査だけを行います。
主な設計判断とその理由は [設計判断と未決事項](spec/03-design-decisions.md) にまとめています。

## 対象とする品質指標

| カテゴリ | 主な指標 | 合格ライン |
| --- | --- | --- |
| 機能テスト | ブランチカバレッジ / ミューテーションスコア / テスト成功率 / スキップされたテスト数 | 75% / 60% 以上（ミューテーションは backend のみ）/ 100% / 前回から増やさない |
| 性能テスト | 応答時間 p95 / スループット / エラー率 | 到達率 50 req/s の負荷条件下で p95 500ms 以内・エラー率 0.1% 以下 |
| セキュリティ | 重大・高 脆弱性件数 / シークレット検出件数 / ライセンス違反件数 | 0 件 / 0 件 / forbidden 0 件（シークレットとライセンスは設定で有効にしたときだけ） |
| コード構造 | 循環的複雑度 15 超の新規関数数（backend と frontend） | 0 件 |
| 契約・互換性 | OpenAPI の破壊的変更件数 | 0 件 |
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

詳細と選定理由は [技術スタック](spec/04-tech-stack.md) を参照してください。

## ディレクトリ構成

```
quality-gate/
├ backend/          Spring Boot 4（Maven）
│  └ src/main/resources/db/migration/   Flyway マイグレーション
├ frontend/         Vue 3 + Vite
│  └ src/api/schema.d.ts                openapi.yml から生成（コミットする）
├ api/openapi.yml   バックエンドから生成（コミットする）
├ collector/        収集ランナー（計測スクリプト・対象ごとの計測プロファイル・ツールの版）
├ docs/             ドキュメント（spec/ は要件定義・設計、operations/ は運用手順）
├ .github/workflows/ collect.yml・collect-target.yml（収集ランナー）/ ci.yml（PR の CI）
└ compose.yaml      PostgreSQL（+ プロファイル full でアプリ）
```
