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

**基本設計 完了（2026-09-21）。実装は未着手です。**

| フェーズ | 状態 |
| --- | --- |
| 要件定義 | 完了（v1.1 確定） |
| 技術スタック | 完了（v1.0 確定） |
| 基本設計（方式・DB・API・画面） | 完了 |
| 詳細設計・実装 | 未着手 |

残る未決事項は Phase 1 の実装と並行して確定できるものに限られます
（[docs/03-open-questions.md](docs/03-open-questions.md)）。
