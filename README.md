# quality-gate

リポジトリの品質指標を計測・取り込みし、合格ラインに対する合格 / 不合格を判定して可視化する社内向け Web アプリケーションです。
計測は quality-gate 側の**収集ランナー**が対象リポジトリを取得して行うため、対象リポジトリには何も置きません。

## クイックスタート

```bash
docker compose up -d db
cd backend && ./mvnw spring-boot:run   # http://localhost:8080（GitHub App の設定と .env が必要）
```

詳しい手順は [開発環境のセットアップ](docs/development/setup.md) を参照してください。

## ドキュメント

### 概要

| ドキュメント | 内容 |
| --- | --- |
| [概要](docs/overview.md) | 目的・対象とする品質指標・技術スタック・ディレクトリ構成 |
| [はじめての人向け: quality-gate のしくみ](docs/architecture/overview-for-beginners.md) | リポジトリ間の関係、ランナー、認証認可、計測の中身をやさしく解説 |
| [実装状況](docs/status.md) | フェーズごとの進捗・動くもの・未実装のもの |

### 開発

| ドキュメント | 内容 |
| --- | --- |
| [開発環境のセットアップ](docs/development/setup.md) | 前提・起動手順・ログイン用 GitHub App の作成と `.env` |
| [コマンド一覧](docs/development/commands.md) | Maven / npm / Docker Compose のコマンドと役割 |
| [起動時のよくある症状](docs/development/troubleshooting.md) | 起動・ログインでつまずいたときの原因と対処 |
| [API の型生成](docs/development/api-codegen.md) | `api/openapi.yml` とフロントエンドの型・fixture の再生成 |
| [アクセシビリティ検査](docs/development/accessibility-check.md) | Playwright + axe-core による検査（M-10 の成果物） |

### アーキテクチャ

| ドキュメント | 内容 |
| --- | --- |
| [起動の仕組み](docs/architecture/runtime.md) | 全体像・起動方法の 3 パターン・フロントエンドとバックエンドの連携 |
| [認証と GitHub App](docs/architecture/authentication.md) | GitHub App の用途・ログインの流れ・認証の経路 |
| [収集ランナー方式](docs/architecture/collector-runner.md) | 対象リポジトリを変更せずに計測する方式の考え方と移行計画（D-16。段階 1〜4 は実装済み） |

### 運用

| ドキュメント | 内容 |
| --- | --- |
| [収集ランナーで計測する](docs/operations/collector.md) | **標準の計測方法。** 対象リポジトリに何も置かずに、quality-gate 側で取得・計測・送信する（手動実行） |
| [取り込み（Ingest API）](docs/operations/ingest.md) | Ingest API の流れと、ローカルでの取り込みの試し方 |
| [セルフホストランナー](docs/operations/self-hosted-runner.md) | 収集ランナーと計測ジョブ用ランナーの準備・登録・リポジトリ変数 |
| [設定値](docs/operations/configuration.md) | 環境変数 / `.env` の一覧と優先順位 |

### 初期ドキュメント（要件定義・設計）

プロジェクト開始時に作成した要件定義と基本設計です。

| ドキュメント | 内容 |
| --- | --- |
| [01 要件定義書](docs/initial/01-requirements.md) | 背景・スコープ・機能要件・非機能要件・アーキテクチャ・ロードマップ |
| [02 指標・判定仕様](docs/initial/02-metrics-spec.md) | 全 10 指標の定義・計算式・入力形式・境界条件 |
| [03 決定事項と残課題](docs/initial/03-open-questions.md) | 決定事項の記録（D-1〜D-22）と残課題 |
| [04 技術スタック](docs/initial/04-tech-stack.md) | 構成・OpenAPI 連携・開発環境・採用しなかった選択肢 |
| [05 方式設計](docs/initial/05-architecture.md) | 状態遷移・ジョブ・正規化・判定・認証認可・エラー処理 |
| [06 データベース設計](docs/initial/06-database-design.md) | テーブル定義・インデックス・保持期間・Flyway 規約 |
| [07 API 設計](docs/initial/07-api-design.md) | エンドポイント・認可マトリクス・エラーコード |
| [08 画面設計](docs/initial/08-screen-design.md) | 画面遷移・ステータス表現・各画面・アクセシビリティ |
