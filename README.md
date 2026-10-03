# quality-gate

**1 つのアプリが品質基準をクリアしているか（リリースしてよいか）を、開発に携わっていない方を含め、誰にでもひと目で分かる形で示す**社内向けの Web アプリケーションです。

- 画面はリリース判定の 1 枚だけです。最上部に結論（**リリース可 / リリース不可**）を大きく出し、その下に品質基準ごとの合否、各基準の意味と根拠、判定の履歴を並べます。印刷して PDF の証跡にできます
- 指標の合否は**合格 / 不合格の 2 値**です（測れなかった指標は計測エラーとして不合格に数えます）
- 合格ラインは**環境変数**（`QG_*`）で決めます。判定に使った合格ラインは計測ごとに残るため、後から変えても過去の結論は変わりません
- ログインは全員で共有する 1 つのアカウントです（GitHub のアカウントは要りません）

計測は quality-gate 側の**収集ランナー**（GitHub Actions + 専有のセルフホストランナー）が対象リポジトリを取得して行います。**対象リポジトリには何も置きません**。
アプリは送られた成果物を取り込み、合格ラインで判定して保存します。取り込める形式は JaCoCo / lcov / PIT / k6 / SARIF / PMD / ESLint / oasdiff / axe-core / JUnit XML です。

```
対象リポジトリ ──clone──▶ 収集ランナー（取得 → 計測 → 送信）──Ingest API──▶ quality-gate（判定・保存・画面）◀── ブラウザ
```

## 品質基準（指標）

| カテゴリ | 指標（ID） | 既定の合格ライン |
| --- | --- | --- |
| 機能テスト | ブランチカバレッジ（M-01）/ ミューテーションスコア（M-02）/ テスト成功率（M-09）/ スキップされたテスト数（M-10） | 75% 以上 / 60% 以上（backend のみ）/ 100% / 前回から増やさない |
| 性能 | 応答時間 p95（M-03）/ エラー率（M-04） | 到達率 50 req/s の負荷の下で 500ms 以内 / 0.1% 以下 |
| セキュリティ | 重大・高 脆弱性件数（M-05）/ シークレット（M-11）/ ライセンス違反（M-12） | 0 件 / 0 件 / 使用禁止 0 件 |
| コード構造 | 循環的複雑度 15 超の関数数（M-06） | 0 件 |
| 契約・互換性 | OpenAPI の破壊的変更件数（M-07） | 0 件 |
| 使いやすさ | アクセシビリティ重大違反件数（M-08） | 0 件 |

対象は **Java / Spring Boot（Maven）+ Vue 3（npm + Vitest）のモノレポ**を想定しています。合格ラインの変え方は [運用](docs/operations.md#24-合格ライン)、各指標の定義は [指標](docs/metrics.md)。

## 使い方

| UC | 利用者 | シナリオ |
| --- | --- | --- |
| UC-01 | 開発者 | 計測プロファイル（`collector/target/profile.env`）とアプリの `QG_REPOSITORY` に対象を書き、合格ラインを環境変数で決める |
| UC-02 | 開発者 | 収集ランナーでブランチ・コミット・タグを指定して計測すると、その場で判定される |
| UC-06 | リリース判断者・開発者 | リリース判定会議で、最新の計測（またはリリースのタグ）の結論と全基準の合否を見て、PDF を証跡として残す |
| UC-07 | リリース判断者 | 判定の履歴で、過去のリリースが品質基準をクリアしてきたかを確かめる |
| UC-08 | 開発者 | リリース不可のとき、不合格の基準の理由と主な違反（どの関数・どの CVE・どの画面）から直す場所を確かめる |

## クイックスタート

```bash
cp .env.example .env                   # QG_LOGIN_PASSWORD（12 文字以上）と QG_INGEST_TOKEN を書き入れる
docker compose up -d db
cd backend && ./mvnw spring-boot:run   # http://localhost:8080（ユーザー名は既定で quality）
```

詳しくは [開発環境](docs/development.md) を参照してください。

## ドキュメント

| ドキュメント | 内容 |
| --- | --- |
| [開発環境](docs/development.md) | セットアップ、コマンド、API の型生成、アクセシビリティ検査、技術スタック、PR の CI、よくある症状 |
| [運用: 環境設定と計測の実行](docs/operations.md) | アプリの設定値（合格ラインの環境変数を含む）と Ingest Token、セルフホストランナー、計測プロファイル、計測の実行、うまくいかないとき |
| [アーキテクチャ](docs/architecture.md) | 全体像、設計判断、測定の仕組み（収集ランナー）、バックエンドの構成、DB 設計、API と画面の共通規則、非機能要件、未決事項 |
| [指標](docs/metrics.md) | 12 の指標の一覧と、それぞれの定義・計測方法・判定の規則 |

### 機能ごとの要件と設計

| 機能 | 要件 | 設計 |
| --- | --- | --- |
| リリース判定（画面） | [requirements](docs/features/release-report/requirements.md) | [design](docs/features/release-report/design.md) |
| 合格ライン（環境変数） | [requirements](docs/features/gate-config/requirements.md) | [design](docs/features/gate-config/design.md) |
| 判定（正規化・判定） | [requirements](docs/features/evaluation/requirements.md) | [design](docs/features/evaluation/design.md) |
| 取り込み（Ingest API） | [requirements](docs/features/ingest/requirements.md) | [design](docs/features/ingest/design.md) |
| 認証（共有アカウント） | [requirements](docs/features/auth/requirements.md) | [design](docs/features/auth/design.md) |

API の型の正本は実装から生成した [`api/openapi.yml`](api/openapi.yml)、DB スキーマの正本は [`db/migration/`](backend/src/main/resources/db/migration/) です。
