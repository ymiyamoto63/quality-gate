# quality-gate

リポジトリの品質指標を計測・蓄積し、あらかじめ決めた合格ラインに対する**合格 / 不合格を判定して可視化する**社内向けの Web アプリケーションです。
リリース前には、タグかコミットを指定して全指標の合否と結論（リリースしてよいか）を 1 画面で確かめ、CSV を証跡として残せます。

- 計測は quality-gate 側の**収集ランナー**（GitHub Actions + 専有のセルフホストランナー）が対象リポジトリを取得して行います。**対象リポジトリには何も置きません**
- アプリは送られた成果物（JaCoCo / lcov / PIT / k6 / SARIF / PMD / ESLint / oasdiff / axe-core / JUnit XML）を取り込み、合格ラインで判定して保存します。テストは実行しません
- 判定は情報として示すだけで、PR のマージは止めません

```
対象リポジトリ ──clone──▶ 収集ランナー（取得 → 計測 → 送信）──Ingest API──▶ quality-gate（判定・保存・画面）◀── ブラウザ
```

## 計測する指標

| カテゴリ | 指標（ID） | 既定の合格ライン |
| --- | --- | --- |
| 機能テスト | ブランチカバレッジ（M-01）/ ミューテーションスコア（M-02）/ テスト成功率（M-09）/ スキップされたテスト数（M-10） | 75% 以上 / 60% 以上（backend のみ）/ 100% / 前回から増やさない |
| 性能 | 応答時間 p95（M-03）/ エラー率（M-04） | 到達率 50 req/s の負荷の下で 500ms 以内 / 0.1% 以下 |
| セキュリティ | 重大・高 脆弱性件数（M-05）/ シークレット（M-11）/ ライセンス違反（M-12） | 0 件 / 0 件 / forbidden 0 件（M-11 / M-12 は有効にしたときだけ） |
| コード構造 | 循環的複雑度 15 超の関数数（M-06） | 0 件 |
| 契約・互換性 | OpenAPI の破壊的変更件数（M-07） | 0 件 |
| 使いやすさ | アクセシビリティ重大違反件数（M-08） | 0 件 |

対象は **Java / Spring Boot（Maven）+ Vue 3（npm + Vitest）のモノレポ**を想定しています。詳しくは [指標](docs/metrics.md)。

## 利用者とできること

| ロール | できること |
| --- | --- |
| Viewer | ダッシュボード・リポジトリ詳細・Run 詳細・違反一覧・トレンド・合格ライン・リリース判定の閲覧と CSV の出力 |
| Admin | Viewer に加えて、Run の再評価、利用者（許可リスト）とロールの管理、監査ログの閲覧 |

ログインは GitHub アカウントで行い、許可リストに登録された人だけが使えます。

| UC | 利用者 | シナリオ |
| --- | --- | --- |
| UC-01 | Admin | `collector/targets/` に計測プロファイルと合格ラインを追加し、対象を計測できるようにする |
| UC-02 | Admin | 収集ランナーでブランチ・コミット・タグ・PR を指定して計測すると、判定結果が生成される |
| UC-03 | Admin | PR を計測し、Run 詳細で不合格の原因（どの関数、どの CVE、どのページ）を特定する |
| UC-04 | 全員 | ダッシュボードで全リポジトリの合否と推移を確かめる |
| UC-05 | Admin | 合格ラインをプルリクエストで変え、マージ後の計測から反映する |
| UC-06 | 全員 | リリース判定会議で、タグかコミットを指定して結論と全指標の合否を確かめ、CSV を証跡として残す |

## クイックスタート

```bash
cp .env.example .env                   # QG_GITHUB_CLIENT_ID / QG_GITHUB_CLIENT_SECRET を書き入れる
docker compose up -d db
cd backend && ./mvnw spring-boot:run   # http://localhost:8080
```

詳しくは [開発環境](docs/development.md) を参照してください。

## ドキュメント

| ドキュメント | 内容 |
| --- | --- |
| [開発環境](docs/development.md) | セットアップ、ログイン用 GitHub App、コマンド、API の型生成、アクセシビリティ検査、技術スタック、PR の CI、よくある症状 |
| [運用: 環境設定と計測の実行](docs/operations.md) | アプリの設定値と Ingest Token、セルフホストランナー、計測対象の追加（計測プロファイル・合格ライン）、計測の実行、うまくいかないとき |
| [アーキテクチャ](docs/architecture.md) | 全体像、設計判断、測定の仕組み（収集ランナー）、バックエンドの構成、DB 設計、API と画面の共通規則、非機能要件、未決事項 |
| [指標](docs/metrics.md) | 12 の指標の一覧と、それぞれの定義・計測方法・判定の規則 |

### 機能ごとの要件と設計

| 機能 | 要件 | 設計 |
| --- | --- | --- |
| 取り込み（Ingest API） | [requirements](docs/features/ingest/requirements.md) | [design](docs/features/ingest/design.md) |
| 合格ライン（S-06） | [requirements](docs/features/gate-config/requirements.md) | [design](docs/features/gate-config/design.md) |
| 判定（正規化・判定・再評価） | [requirements](docs/features/evaluation/requirements.md) | [design](docs/features/evaluation/design.md) |
| ダッシュボード（S-01） | [requirements](docs/features/dashboard/requirements.md) | [design](docs/features/dashboard/design.md) |
| Run の閲覧（S-02 / S-03 / S-04） | [requirements](docs/features/run-detail/requirements.md) | [design](docs/features/run-detail/design.md) |
| トレンド（S-05） | [requirements](docs/features/trends/requirements.md) | [design](docs/features/trends/design.md) |
| リリース判定（S-08） | [requirements](docs/features/release-report/requirements.md) | [design](docs/features/release-report/design.md) |
| 認証と認可 | [requirements](docs/features/auth/requirements.md) | [design](docs/features/auth/design.md) |
| 管理（S-07。利用者と監査ログ） | [requirements](docs/features/admin/requirements.md) | [design](docs/features/admin/design.md) |
| 保持期間と日次バッチ | [requirements](docs/features/retention/requirements.md) | [design](docs/features/retention/design.md) |

API の型の正本は実装から生成した [`api/openapi.yml`](api/openapi.yml)、DB の列の定義の正本は [`V001__init.sql`](backend/src/main/resources/db/migration/V001__init.sql) です。
