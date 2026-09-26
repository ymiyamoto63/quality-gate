# 収集ランナー方式

本書は、収集ランナー（quality-gate 側で対象リポジトリを取得・計測し、Ingest API に送る仕組み）の構成と、
その考え方をまとめたものです。手順は [収集ランナーで計測する](../operations/collector.md) を参照してください。

## 1. 要点

- 計測は quality-gate リポジトリ（private）の GitHub Actions ＋ 専有のセルフホストランナーで行う。
  **対象リポジトリには設定ファイル・ワークフロー・計測用の依存関係を置かない**
- バックエンドは送られた成果物を取り込んで判定するだけで、**テストを実行しない**（[03](../spec/03-design-decisions.md) DD-6）
- Ingest API の送り手は収集ランナーだけ。合格ライン（`*.gate.yml`）も収集ランナーが Run ごとに送る（DD-13）
- 計測は手動実行で起動する（DD-7）。PIT と負荷試験は PR 以外の計測で実行する（DD-17）
- 比較元・タグ・ファイルの移動は、収集ランナーが clone した履歴から求めて送る（DD-10）
- 対象のビルド構成への追従は、対象のチームではなく quality-gate の運用者が担う

## 2. 前提条件

[要件定義書 2.1](../spec/01-requirements.md) の前提のうち、本方式に影響するものです。

| 前提 | 本方式への影響 |
| --- | --- |
| 対象は **1 リポジトリ**、構成は Spring Boot + Vue 3 のモノレポ | 対象の構成が決まっているため、ビルド方法の自動検出を作り込まずに済む。**方式の難度を大きく下げる要因** |
| 個人アカウント（GitHub Free）、Organization なし | GitHub App の追加インストールは所有者本人が行えるため、承認のやり取りは不要 |
| 対象リポジトリは **private** | clone に読み取り権限が必要（3.3）。収集ランナーの実行ログ・成果物も private に閉じる必要がある |
| private リポジトリの Actions は月 2,000 分 | 計測はセルフホストランナーで行うため枠を消費しない |
| 性能計測は専有ランナーで、他のジョブと同居させない（[03](../spec/03-design-decisions.md) DD-11） | 収集ジョブと性能計測が同じランナーで重ならないよう直列化する（3.5） |

## 3. 構成

### 3.1 全体像

```
                        （対象リポジトリには何も置かない）
 対象リポジトリ (private) ──clone（読み取りのみ）──┐
                                                   │
 quality-gate リポジトリ (private)                 │
   .github/workflows/collect.yml                   │
   collector/                                      ▼
     targets/<owner>__<name>.env      ──►  セルフホストランナー
     targets/<owner>__<name>.gate.yml       1. clone、比較元・タグ・ファイルの移動を求める（fetch）
     bin/*.sh                               2. 計測（measure。コンテナの中。ツールは版を固定）
     versions.env                           3. Ingest API へ送信（submit。合格ラインも送る）
                                                   │
                                                   ▼
                                  quality-gate バックエンド（取り込み → 判定 → 可視化）
```

### 3.2 実行基盤の選択肢

| 案 | 内容 | 評価 |
| --- | --- | --- |
| **A. quality-gate リポジトリの Actions**（採用） | `collect.yml` を手動実行し、専有のセルフホストランナーで計測する | ランナー・シークレット・ログ閲覧を既存の仕組みで賄える。追加の常駐プロセスが不要 |
| B. バックエンドの中で実行 | バックエンドから Docker で計測コンテナを起動する | バックエンドは取り込みと判定だけを行う方針（DD-6）に反する。バックエンドのホストに Docker ソケット（実質 root）を渡し、DB と同じ場所で対象のビルドコードを実行することになる。**不採用** |
| C. 独立した収集デーモン | ランナー機に常駐プロセスを置き、quality-gate をポーリングする | 起動タイミングの自由度は高いが、監視・再起動・ログの仕組みを自前で持つ必要がある。対象 1 件の規模には過剰 |

### 3.3 認証情報

| 用途 | 認証情報 | 置き場所 |
| --- | --- | --- |
| 対象リポジトリの clone | ログインと同じ GitHub App に **Contents / Pull requests: Read-only** を付け、対象リポジトリにインストールする。ジョブごとに `actions/create-github-app-token` で**対象リポジトリに限定した 1 時間有効のトークン**を発行する | quality-gate の Actions Secrets（App ID と秘密鍵） |
| Ingest API への送信 | Ingest Token（すべての対象で共通の 1 つ。DD-20） | quality-gate の Actions Secrets（`QG_INGEST_TOKEN`） |

デプロイキーや個人アクセストークンは、対象ごとの管理や個人への依存が生じるため使いません。

### 3.4 指標ごとの計測方法

ツールの版は quality-gate 側で固定します。対象リポジトリにプラグインの設定があっても使いません（計測条件をそろえるため）。
ただし**テストそのものは対象リポジトリのもの**を実行します。

| 指標 | 計測方法 |
| --- | --- |
| M-01（Java） | `mvn org.jacoco:jacoco-maven-plugin:<ver>:prepare-agent verify org.jacoco:jacoco-maven-plugin:<ver>:report` |
| M-01（TS） | 作業用の clone に `@vitest/coverage-v8` を入れ、`vitest run --coverage.enabled --coverage.reporter=lcov` |
| M-02 | PIT のコマンドライン版を、`mvn dependency:build-classpath` で得たクラスパスで実行する。pom を書き換えずに済む（PR 以外） |
| M-03〜05 | バックエンドの jar を起動し、quality-gate 側の k6 のシナリオで負荷をかける（PR 以外） |
| M-06 / M-13 / M-14 | `trivy fs`（版固定。脆弱性・シークレット・ライセンス） |
| M-07 | PMD / ESLint を quality-gate 側の設定で、**head と base の両方**について実行し、`scope=base` も送る |
| M-09 | 計測プロファイルで指定した OpenAPI 定義を head と base で取り出し、oasdiff で比較する |
| M-10 | バックエンドの jar と `vite preview` を起動し、quality-gate 側の Playwright + axe-core で計測プロファイルの `A11Y_PAGES` を検査する |
| M-11 / M-12 | backend の Surefire / Failsafe と frontend の Vitest の JUnit XML |

**計測プロファイル**は、対象ごとに異なる計測上の情報を quality-gate 側で持つファイルです。
合格ラインではなく「どう測るか」だけを書きます（合格ラインは同じディレクトリの `*.gate.yml`）。
追加のツールなしにシェルで読めるよう `KEY=VALUE` 形式にしています（シェルとしては実行しません）。

```bash
# collector/targets/ymiyamoto63__like-chatgpt.env（抜粋）
QG_REPOSITORY=ymiyamoto63/like-chatgpt
DEFAULT_BRANCH=main
BACKEND_DIR=backend
JAVA_VERSION=21
OPENAPI_PATH=api/openapi.yml                                       # M-09
FRONTEND_DIR=frontend
FRONTEND_COVERAGE_INCLUDE=src/**/*.{ts,vue}
A11Y_PAGES=/                                                       # M-10
```

### 3.5 実行時の安全対策

収集ランナーは**対象リポジトリのビルド・テストのコードを実行します**。対象は自分のリポジトリですが、
ビルドの依存関係まで含めると信頼しきれないため、次の対策をとっています。

| リスク | 対策 |
| --- | --- |
| 対象のテストコードが同じジョブのシークレット（App のトークン、Ingest Token）を読む | **ジョブを 3 つに分ける**。取得（App トークンを使う）→ 計測（シークレットを渡さない）→ 送信（Ingest Token を使う）。ジョブ間は Actions の成果物で受け渡す（保持期間 1 日） |
| 前回の計測の残骸や、計測中に書き換えられたファイルが次回に影響する | 作業領域はジョブの最後に必ず削除する |
| 対象のビルド・テストのコードがランナーのマシンに触れる | **計測をコンテナに隔離する**（`collector/bin/measure-isolated.sh`）。コンテナに見せるのは作業領域・`reports/`・`collector/`（読み取りのみ）・キャッシュのボリュームだけで、権限を落とし（`--cap-drop ALL`）、ランナーの利用者の UID で動かす。外向きの通信は依存関係の取得のために残す（[運用手順](../operations/collector.md#7-計測のコンテナ隔離)） |
| private のソースやテスト出力がログから漏れる | quality-gate リポジトリを private に保つ。ログにソースを出さない |
| フォークからの PR で任意のコードが実行される | 計測するのは同一リポジトリ内のブランチからの PR だけにする |
| 性能計測と同じランナーで重なり、性能値が乱れる（DD-11） | ランナーを 1 台にして、ジョブを 1 つずつ実行させる（収集ワークフローも `max-parallel: 1`）。重なりが問題になるほど頻度が上がったら、収集用のランナーを別に用意する |

## 4. 決めていること

| # | 論点 | 決定 |
| --- | --- | --- |
| 1 | M-10 の方式 | **対象アプリをランナー上で起動して検査する**（検証環境の URL はコミットと画面が対応しないため使わない）。外部のサービスが要る対象とログインが要る画面は、必要になった時点で拡張する |
| 2 | 計測プロファイルの置き場所 | quality-gate リポジトリのファイル。対象が増えたら見直す |
| 3 | 収集ジョブと性能計測のランナーを分けるか | 同じランナーで直列化する。性能計測は収集ジョブの中で行うため、別のジョブと重なることはない |
