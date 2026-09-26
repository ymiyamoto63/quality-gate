# 収集ランナーで計測する

対象リポジトリに**何も置かずに**、quality-gate 側で対象を取得・計測して取り込む手順です。
これが対象リポジトリを計測する標準の方法です（[決定事項 D-16](../initial/03-open-questions.md)）。
方式の考え方と移行の記録は [収集ランナー方式](../architecture/collector-runner.md)、
しくみの全体像は [はじめての人向け: quality-gate のしくみ](../architecture/overview-for-beginners.md) を参照してください。

計測する指標は **M-01〜M-14 の全指標**です（M-08 は欠番）。
M-07（循環的複雑度）は backend と frontend の両方を解析します。
M-02（PIT）と M-03〜05（性能）は時間がかかるため、**PR の計測では実行せず**スキップを申告します。
ブランチ・コミット・タグの計測ではすべて実行します（リリースブランチやタグも完全計測にし、リリース判定に使えるようにするため。D-24）。

| 内容 | 詳細 |
| --- | --- |
| 手動実行（`workflow_dispatch`）で 1 コミット（ブランチ・コミット・タグ・PR）を計測する | [2. 手動で実行する](#2-手動で実行する) |
| **M-02（PIT）**を PR 以外の計測で全量実行する | [5. M-02（PIT）](#5-m-02pit) |
| **M-10（アクセシビリティ）**。対象アプリをランナー上で起動し、画面を axe-core で検査する | [6. M-10（アクセシビリティ）](#6-m-10アクセシビリティ) |
| **計測のコンテナ隔離**。対象のビルド・テストのコードはコンテナの中だけで動く | [7. 計測のコンテナ隔離](#7-計測のコンテナ隔離) |
| **M-03〜05（性能）**。対象のバックエンドをランナー上で起動し、k6 で負荷をかける（PR 以外） | [8. M-03〜05（性能）](#8-m-0305性能) |

## 構成

| ファイル | 役割 |
| --- | --- |
| `.github/workflows/collect.yml` | 入り口。手動実行（`workflow_dispatch`）の入力を受け取り、`collect-target.yml` を呼ぶ |
| `.github/workflows/collect-target.yml` | 1 コミットの計測。`fetch`（取得）→ `measure`（計測）→ `submit`（送信）の 3 ジョブ。すべてセルフホストランナーで動く |
| `collector/bin/fetch.sh` | 対象を clone し、計測するコミットと比較元（base）を決めて `meta.env` に書く |
| `collector/bin/measure-isolated.sh` | `measure.sh` を計測用のコンテナの中で実行する（イメージが無ければ作る）。`measure` ジョブはこれを呼ぶ |
| `collector/bin/measure.sh` | 計測して成果物を `reports/` にまとめる。**認証情報を受け取らない**。持つのは準備と実行の順序だけで、指標ごとの計測は `collector/bin/measure/` にある |
| `collector/bin/measure/` | 指標ごとの計測（`backend-tests.sh` = M-01 Java / M-11 / M-12、`frontend-tests.sh` = M-01 TS / M-11 / M-12、`mutation.sh` = M-02、`performance.sh` = M-03〜05、`vulnerabilities.sh` = M-06 / M-13、`complexity.sh` = M-07、`breaking-changes.sh` = M-09、`accessibility.sh` = M-10、`licenses.sh` = M-14）と、複数の指標で共用するもの（`common.sh`。比較元の作業ツリー、サーバと画面の起動、ツールの用意、Trivy）。`measure.sh` が source する |
| `collector/bin/submit.sh` | Ingest API に送る。合格ライン（`*.gate.yml`）も Run ごとに送る |
| `collector/versions.env` | ツールの版（JaCoCo / PIT / PMD / oasdiff / Trivy / Maven）。対象の設定に関係なくこの版で計測する |
| `collector/runner/Dockerfile` | 計測用のコンテナ（JDK・Node.js・Maven・Trivy・oasdiff・Playwright と Chromium） |
| `collector/pmd-ruleset.xml` | M-07 のルールセット（全メソッドの CC を出力する） |
| `collector/pit/pom.xml` | M-02 で使う PIT 一式の取得用（ビルドはしない。クラスパスを得るだけ） |
| `collector/a11y/` | M-10 の検査スクリプト（`scan.mjs`）と、Playwright・axe-core の版を固定した `package.json` / `package-lock.json` |
| `collector/complexity/` | M-07（frontend）の ESLint の設定（`eslint.config.mjs`。`complexity` ルールだけを上限 0 で動かす）と、ESLint・パーサの版を固定した `package.json` / `package-lock.json` |
| `collector/targets/<owner>__<name>.env` | 計測プロファイル（どう測るか） |
| `collector/targets/<owner>__<name>.gate.yml` | 合格ライン。判定はこのファイルで行う（D-20。無ければ送信しない） |
| `collector/targets/<owner>__<name>.k6.js` | M-03〜05 の負荷試験のシナリオ（k6） |

### 指標ごとの計測方法

| 指標 | 方法 |
| --- | --- |
| M-01（Java） | `mvn org.jacoco:jacoco-maven-plugin:<版>:prepare-agent verify ...:report`。JaCoCo をコマンドラインから差し込む。単体テストと結合テスト（failsafe）の両方を 1 つの実行データに集める |
| M-01（TS） | `vitest run` にカバレッジのオプションを渡す。`@vitest/coverage-v8` が対象に無ければ、Vitest と同じ版を作業用の clone にだけ入れる（`npm install --no-save`） |
| M-06 / M-13 | コミット時点の作業ツリーに依存関係を取得した後で `trivy fs --scanners vuln,secret`（対象の CI と同じ順序）。メタデータ `{"scanners":["vuln","secret"]}` を添えて送り、脆弱性は M-06、シークレットは M-13 で判定される |
| M-14 | 同じ作業ツリーを `trivy fs --scanners license` で走査する（深刻度で絞らない。デュアルライセンスの緩いほうを選ぶため）。`trivy-license.sarif` をメタデータ `{"scanners":["license"]}` を添えて送る |
| M-07（Java） | PMD のコマンドライン版で `src/main/java` を解析する。**head と base の両方**を解析し、base は `scope=base` で送る |
| M-07（TS） | quality-gate 側の ESLint の設定（`collector/complexity`）で `FRONTEND_COMPLEXITY_SOURCES`（既定: `src`）を解析する。対象の ESLint の設定は使わない。**head と base の両方**を解析し、ESLint が出す絶対パスを `/<FRONTEND_DIR>/src/...` にそろえてから `eslint-json` で送る |
| M-11 / M-12 | backend は `mvn verify` が出す JUnit XML のうち `TEST_REPORTS`（既定: `surefire-reports/TEST-*.xml failsafe-reports/TEST-*.xml`）に合うものすべて、frontend は Vitest に junit reporter を足して出した `junit.xml` を `test-junit-xml` で送る |
| M-09 | コミットされている OpenAPI 定義を head と base で取り出し、oasdiff で比べる。base に定義が無ければ「新規 API」として送る |
| M-03〜05 | バックエンドの jar を起動し、計測プロファイルの `PERF_SCRIPT`（k6 のシナリオ）で API に負荷をかける。3 回実行し、それぞれの summary を送る |
| M-10 | バックエンドの jar と `vite build` した画面（`vite preview`）を起動し、計測プロファイルの `A11Y_PAGES` をライト・ダークの両方で axe-core により検査する |

テストが失敗しても計測は止めません（失敗は M-11 の判定材料として送ります）。
ビルド自体に失敗した場合など、成果物が出なかった指標は送られず、quality-gate では ERROR になります。

比較元（base）は、新規の違反（M-07）・破壊的変更（M-09）・スキップの増加（M-12）を数える起点です。次の順に決めます。

1. 入力 `base`（コミットかタグ）を指定したときは、それ
2. `commit` に**タグ**を指定したときは、その前のタグ（`git describe --tags`）。前のタグが無ければ直前のコミット。
   リリース判定では「前回のリリースから何が増えたか」を見るため
3. 既定ブランチを計測するときは直前のコミット、それ以外のブランチや PR を計測するときは既定ブランチとの merge-base（対象の CI と同じ）

quality-gate は、比較元のコミットで判定済みの Run があれば、それを比較対象 Run（前回比・新規 / 継続 / 解消の起点）にします。
無ければ、同じブランチで直前に計測した Run です。

取得（`fetch`）では、あわせて次の 2 つを対象の履歴から求めて送ります。quality-gate のバックエンドは GitHub API を呼びません（D-26）。

| 送るもの | 求め方 | 使い道 |
| --- | --- | --- |
| タグ（Run の `tags`） | 計測するコミットを指すタグ（`git tag --points-at`） | リリース判定（S-11）でタグをコミットに解決する |
| ファイルの移動（成果物 `git-renames`） | 比較元からの `git diff -M` と、first-parent 1,000 コミット分のコミットごとの `git log -M`（`collector/bin/renames.sh`） | 移動しただけのファイルの違反を新規・解消として扱わない（[指標仕様書 0.4](../initial/02-metrics-spec.md)） |

## 1. 事前の準備

### 1-1. セルフホストランナー

quality-gate の既存のセルフホストランナー（[セルフホストランナー](self-hosted-runner.md)）をそのまま使います。
追加で必要なのは次のものです。

| 必要なもの | 使う箇所 |
| --- | --- |
| Docker（ランナーの利用者が `docker` を使えること） | 計測用のコンテナ（[7 章](#7-計測のコンテナ隔離)）。JDK・Node.js・Chromium などはコンテナに入るため、ランナーに入れる必要はない |
| `curl` / `jq` | Node.js の版の解決、送信 |
| Docker Hub への外向き通信 | 計測用のコンテナのベース（`eclipse-temurin`）と、Ubuntu のパッケージ |
| github.com / nodejs.org / archive.apache.org / npm レジストリへの外向き通信 | 対象の clone、Trivy・oasdiff・PMD・Node.js・Maven・Playwright の取得、Maven Central と npm の依存関係 |
| quality-gate への到達性 | `submit` ジョブもセルフホストランナーで動く |

計測プロファイルで `ISOLATION=none` にした対象は、コンテナを使わずランナー上で直接計測します。
その場合は、加えて `unzip`、Chromium の動作に必要なライブラリ（`sudo npx playwright install-deps chromium` を一度）、
空いているポート 8080 / 4173（M-10 と M-03〜05 で対象アプリを起動する）が必要です。

ランナーは 1 台なので、収集ジョブは同時には動きません（`max-parallel: 1`）。
性能計測（M-03〜05）の値が他のジョブに乱されないよう、**このマシンには他のランナーや常駐サービスを置かないでください**（D-7 の「同居させない」）。

### 1-2. 対象を読むための GitHub App（private リポジトリの場合）

ログインに使っている GitHub App に権限を足し、対象リポジトリにインストールします。

1. GitHub の **Settings → Developer settings → GitHub Apps → （quality-gate の App） → Permissions & events** で
   **Repository permissions** の **Contents** と **Pull requests** を **Read-only** にする
2. 同じ画面の **Private keys** で秘密鍵を生成し、ダウンロードする
3. **Install App** から対象リポジトリ（like-chatgpt）にインストールする（**Only select repositories** で対象だけを選ぶ）

対象が public なら App は不要です（`QG_COLLECTOR_APP_ID` を設定しなければトークンなしで取得します）。

### 1-3. quality-gate リポジトリの変数とシークレット

**quality-gate リポジトリ**の **Settings → Secrets and variables → Actions** で設定します。
対象リポジトリには何も設定しません。

| 種別 | 名前 | 値 |
| --- | --- | --- |
| Variables | `QG_BASE_URL` | 取り込み先の quality-gate の URL（セルフホストランナーから到達できるもの） |
| Variables | `QG_COLLECTOR_APP_ID` | 1-2 の App の App ID |
| Secrets | `QG_COLLECTOR_APP_PRIVATE_KEY` | 1-2 でダウンロードした秘密鍵（PEM の中身全体） |
| Secrets | `QG_INGEST_TOKEN` | Ingest Token。バックエンドの環境変数 `QG_INGEST_TOKEN` と同じ値（すべての対象で共通。D-27。[作り方](ingest.md#ingest-token-の作成と交換)） |

**quality-gate リポジトリは private のままにしてください。** 収集ワークフローのログと成果物には、対象のパスやテスト出力が含まれます。

### 1-4. quality-gate にリポジトリを登録する

1. **管理 › リポジトリ管理（S-08）** で like-chatgpt を登録する。登録していないリポジトリの Run は受け付けない

合格ラインは `collector/targets/ymiyamoto63__like-chatgpt.gate.yml` です。収集ランナーが Run ごとに送り、quality-gate はその内容で判定します（D-20）。
画面（S-06）は表示するだけで、編集はできません。変更はプルリクエストで行い、main にマージした後の計測から使われます
（すぐに反映したいときは collect ワークフローを手動実行する）。内容が変わったときだけ新しい版として S-06 の「変更履歴」に残ります。

この設定は `execution.skippable_metrics` に `mutation_score` と `performance` を入れています。PR の計測では M-02 と M-03〜05 のスキップを申告するため、
これが無いと申告が受け付けられず、PR の Run の M-02 / M-03〜05 が ERROR になります。

## 2. 手動で実行する

計測は手動実行だけです。同じコミットを何度でも計測できます（Run は試行として別に残ります）。

1. quality-gate の **Actions → collect → Run workflow** を開く
2. 入力して実行する

   | 入力 | 例 | 説明 |
   | --- | --- | --- |
   | repository | `ymiyamoto63/like-chatgpt` | 計測プロファイルがあるリポジトリ |
   | branch | （空） | 空なら既定ブランチ。PR の場合は PR のブランチ名 |
   | commit | （空）/ `v1.2.0` | 特定のコミット（40 桁）かタグを測るときに指定する |
   | pull_request | （空） | PR を計測するときの番号 |
   | base_branch | （空） | 比較元を決めるブランチ。空なら既定ブランチ。PR のマージ先が既定ブランチ以外のときに指定する |
| base | （空）/ `v1.1.0` | 比較元のコミット（40 桁）かタグ。空なら自動（上の決め方） |

3. 実行画面に対象のまとまり（例: `ymiyamoto63/like-chatgpt main`）ができる。その中の `submit` ジョブのログに `Run を作成しました: <runId>` と送信したファイル、
   最後に判定結果（`判定: PASS` など）が出ていることを確かめる。判定に失敗した（設定の誤りなど）場合は `submit` ジョブが失敗する
4. quality-gate の Run 詳細で結果を見る。収集ランナーの Run は `triggeredBy` が `collector` になる

`measure` ジョブの成果物 `collector-reports`（7 日保持）で、送った内容をあとから確認できます。

### リリース判定のために計測する

リリース判定（画面 S-11）は、指定したコミットの**完全計測**の Run で結論を出します。

1. `commit` にリリースのタグ（例: `v1.2.0`）を入れて実行する。branch と base は空のままでよい
   （比較元は前のタグになる。前のリリースと比べたくないときだけ `base` を指定する）
2. 計測が終わったら（約 20 分）、quality-gate のリポジトリ詳細 → リリース判定で同じタグを入れる

リリース判定は、計測したときにコミットを指していたタグでコミットを探します。タグを付ける前に計測したコミットや、
この仕組みより前（V020 より前）の計測は、タグでは見つかりません。タグを付けてから計測し直すか、コミット SHA で指定してください。

前のタグのコミットも計測しておくと、前回比（例: カバレッジが何ポイント下がったか）と、違反の新規 / 継続 / 解消が前のリリースとの比較になります。

## 3. 対象自身のビルドと結果が一致するかを確かめる

対象を新しく追加したときは、同じコミットについて、対象自身のビルドの出力と収集ランナーの結果を比べます。

| 指標 | 一致すべきもの |
| --- | --- |
| M-01 | backend / frontend それぞれのブランチカバレッジ |
| M-02 | ミューテーションの総数と検出数（PR 以外）。対象のテストが乱数を固定していないと、同じ方式でも実行ごとに数件ずれる |
| M-06 | 件数（Trivy の版の違いで差が出うる。差が出たら `versions.env` の版を揃えて確かめる） |
| M-07 | CC 15 超の関数の数（収集ランナーは base と比べ、新しく増えたもの・悪化したものだけを違反にする） |
| M-09 | 破壊的変更の件数（または「対象外」） |
| M-10 | 検査した画面と、重大（critical / serious）の違反の件数。対象の e2e が API をモックしている場合、収集ランナーは実際のバックエンドにつなぐため、API の応答で描画が変わる画面では違反が変わりうる |
| M-11 / M-12 | テストの件数（成功・失敗・スキップ）。`mvn verify` / `npx vitest run` の出力の件数と一致すること |

参考までに、like-chatgpt の `b581260`（main）を手元で計測した結果は、JaCoCo・lcov・PMD の各数値と
テストの件数が、like-chatgpt 自身のビルド（`mvn verify` / `npm run test:coverage`）の出力と一致しました。
M-02 はミューテーションの総数（143 件）が like-chatgpt 自身の `mvn -P mutation test` と一致し、検出数は 98〜99 件でした。
1 件の差は、乱数を使うクラス（`RandomWalkMetricsGenerationAdapter`）のテストの結果が実行ごとに変わるためで、
like-chatgpt 自身の方式で繰り返しても、検出されるミューテーションが入れ替わります。

## 5. M-02（PIT）

| 項目 | 内容 |
| --- | --- |
| 実行する計測 | **PR 以外の計測**（ブランチ・コミット・タグ） |
| 実行範囲 | 常に全量（`mutationScope: all`）。変更範囲への絞り込みはしない |
| PR の計測 | 実行せず、M-02 のスキップを申告する（Run 詳細では SKIP と理由が出る） |
| 実行方法 | PIT のコマンドライン版を、対象のテストのクラスパス（`mvn dependency:build-classpath`）で動かす。対象の pom の PIT の設定は使わない |
| 対象クラス | 計測プロファイルの `MUTATION_TARGET_CLASSES` など（下記） |
| 所要時間 | like-chatgpt で約 1 分（2 スレッド） |

計測プロファイルの設定:

| キー | 説明 |
| --- | --- |
| `MUTATION_TARGET_CLASSES` | ミューテーションを加えるクラス（PIT の `targetClasses`。空白区切り）。**空にすると M-02 を計測しない** |
| `MUTATION_TARGET_TESTS` | 実行するテスト（空なら `MUTATION_TARGET_CLASSES` と同じ） |
| `MUTATION_EXCLUDED_CLASSES` | 除外するクラス（起動クラスや設定クラスなど） |
| `MUTATION_EXCLUDED_TESTS` | 除外するテスト（結合テスト `*IT` など、時間のかかるもの） |
| `MUTATION_THREADS` | PIT のスレッド数（既定: 2） |

注意:

- **テストが 1 件でも失敗していると PIT は動きません**（M-02 が ERROR になります）。M-01 と M-11 は失敗したテストがあっても送られます
- PR 以外の計測のたびに実行します
- 生き残ったミューテーションの一覧は quality-gate には保存しません（M-02 は違反を作らない）。
  個々に見たいときは、手元で PIT の HTML レポートを出してください

## 6. M-10（アクセシビリティ）

| 項目 | 内容 |
| --- | --- |
| 方式 | 対象アプリを**計測用のコンテナの中で起動**して検査する（共有の検証環境の URL は使わない。コミットごとの画面を検査するため） |
| 起動するもの | バックエンド: `measure_backend` のビルドで出来た実行可能 jar（`java -jar`）。フロントエンド: `vite build` の結果を `vite preview` で配る。`/api` の proxy は対象の `vite.config` の `server.proxy` がそのまま使われる |
| 検査する画面 | 計測プロファイルの `A11Y_PAGES`。合格ライン（`*.gate.yml`）の `accessibility.pages` と一致させる（一致しないと ERROR） |
| 検査の内容 | 各画面をライト・ダークの 2 通りで開き、WCAG 2.2 AA のタグ（`wcag2a` 〜 `wcag22aa`）で axe-core を実行する。対象の e2e と同じ条件 |
| 実行する計測 | すべての計測（既定ブランチも PR も） |
| 所要時間 | like-chatgpt で約 1 分（ツールの取得を含む） |
| ツールの版 | `collector/a11y/package-lock.json` で固定（Playwright・axe-core）。対象の `package.json` にある版は使わない |

計測プロファイルのキー:

| キー | 説明 |
| --- | --- |
| `A11Y_PAGES` | 検査する画面のパス（空白区切り）。**空にすると M-10 を計測しない** |
| `A11Y_READY_SELECTOR` | 描画が済んだと判断できる要素（CSS セレクタ）。空なら通信が落ち着くまで待つだけ |
| `A11Y_BACKEND_PORT` | バックエンドを起動するポート。対象のフロントエンドの proxy 先に合わせる。**空にするとバックエンドを起動しない**（画面だけで描ける対象） |
| `A11Y_FRONTEND_PORT` | `vite preview` のポート（既定: 4173） |
| `A11Y_START_TIMEOUT` | 起動を待つ秒数（既定: 120） |

注意:

- **データベースなど外部のサービスが要る対象は、今のしくみでは起動できません。** like-chatgpt のバックエンドは外部のサービスを使わずに起動します。
  必要になったら、コンテナでの起動（Docker Compose）を計測プロファイルで指定できるようにします
- ログインが必要な画面は検査できません（ログインの手順を持たないため）。検査できるのは、ログインせずに表示できる画面だけです
- 読み込めなかった画面（起動の失敗、応答が 4xx / 5xx、`A11Y_READY_SELECTOR` が現れない）は結果に含めません。
  `accessibility.pages` との照合で M-10 が ERROR になり、失敗が合格に見えることはありません
- 検査の結果は `reports/frontend/axe-results.json`（`axe-json`、component は frontend）として送ります

## 7. 計測のコンテナ隔離

`measure` ジョブは `collector/bin/measure-isolated.sh` を呼び、`measure.sh` を**計測用のコンテナの中で**実行します。
対象のビルド・テストのコード（Maven のプラグイン、npm の依存関係のスクリプトも含む）は、このコンテナの中だけで動きます。

| 項目 | 内容 |
| --- | --- |
| コンテナに見せるもの | 作業ディレクトリ（取得したソース）と `reports/`（読み書き）、`collector/`（読み取りのみ）、キャッシュ用のボリューム `quality-gate-collector-home`（Maven・npm・PMD・Trivy の DB） |
| 見せないもの | ランナーのマシンの他のファイル、Docker のソケット、他のジョブの作業領域、GitHub の認証情報（そもそも `measure` ジョブに無い） |
| 権限 | ランナーの利用者の UID で動かし、`--cap-drop ALL` と `no-new-privileges` で権限を落とす。メモリ（既定 6 GB）とプロセス数に上限を付ける |
| 通信 | 外向きの通信はできる（Maven Central・npm から依存関係を取るため）。M-10 で起動する対象アプリのポートはコンテナの中に閉じ、ランナーのポートを使わない |
| イメージ | `collector/runner/Dockerfile`。JDK は計測プロファイルの `JAVA_VERSION`、Node.js は対象の `.nvmrc` の版で、初回の計測でビルドする。版と Dockerfile が同じなら作り直さない |
| Trivy / oasdiff | コンテナの中から Docker は使えないため、`versions.env` の版と同じバイナリをイメージに入れて使う |

設定:

| 設定 | 説明 |
| --- | --- |
| 計測プロファイルの `ISOLATION` | `container`（既定）か `none`（コンテナを使わずランナー上で直接実行する。1-1 の追加の準備が要る） |
| 環境変数 `QG_COLLECTOR_MEMORY` | コンテナのメモリ上限（既定: `6g`） |
| 環境変数 `QG_COLLECTOR_CPUS` | コンテナの CPU 上限（既定: 制限しない） |
| 環境変数 `QG_COLLECTOR_DOCKER_ARGS` | `docker run` に足す引数（空白区切り）。社内のプロキシを通すときなどに使う（例: `--env HTTPS_PROXY --env JAVA_TOOL_OPTIONS`）。**ソケットやホストのディレクトリを見せる引数は足さない**（隔離の意味が無くなる） |
| 環境変数 `QG_COLLECTOR_BASE_IMAGE` | ベースのイメージ（既定: `eclipse-temurin:<JAVA_VERSION>-jdk-noble`）。社内のミラーや、社内の CA を入れたイメージを使うときに指定する |

注意:

- 初回の計測（とツールの版を上げた後）はイメージのビルドに数分かかります。古いイメージは `docker image prune` で消せます
  （タグは `quality-gate-collector:java<版>-node<版>-...`）
- キャッシュのボリュームを消すと（`docker volume rm quality-gate-collector-home`）、次の計測で依存関係を取り直します
- 隔離は「対象のコードからランナーのマシンを守る」ためのものです。コンテナから外への通信は制限しないため、
  対象のコードが取得したソースを外に送ることは防げません（対象は自分たちのリポジトリに限る方針は変わりません）

## 8. M-03〜05（性能）

| 項目 | 内容 |
| --- | --- |
| 実行する計測 | **PR 以外の計測**（ブランチ・コミット・タグ）。PR の計測では M-03〜05 のスキップを申告する |
| 方式 | `measure_backend` のビルドで出来たバックエンドの jar を起動し、k6 で API に負荷をかける。画面（静的アセット）は対象にしない |
| シナリオ | 計測プロファイルの `PERF_SCRIPT`（`collector/targets/` のファイル）。like-chatgpt は `chat`（25 req/s）・`suggest`（15 req/s）・`monitoring`（10 req/s）の合計 50 req/s |
| 計測条件 | 仕様（[指標・判定仕様](../initial/02-metrics-spec.md) M-03）のとおり。到達率一定、ウォームアップ 60 秒を除き 300 秒計測、3 回実行して quality-gate が中央値で判定する |
| 所要時間 | 1 回 約 6 分 × 3 回 = **約 18 分**（起動を含む） |
| ツールの版 | `collector/versions.env` の `K6_VERSION`。初回にキャッシュ（`quality-gate-collector-home`）へ取得する |
| 送るもの | `k6-summary` を 3 ファイル（component はバックエンド）。metadata の `environment` に計測環境の名前・CPU 数・メモリ・シードデータ・k6 の版を入れる。k6 が異常終了した回は `aborted: true` を付けて送り、その Run の M-03〜05 は ERROR になる |

計測プロファイルのキー:

| キー | 説明 |
| --- | --- |
| `PERF_SCRIPT` | k6 のシナリオ（`collector/targets/` からの相対）。**空にすると M-03〜05 を計測しない** |
| `PERF_BACKEND_PORT` | バックエンドを起動するポート（既定: 8080） |
| `PERF_ENVIRONMENT` | 計測環境の名前（既定: `collector`）。前回比とトレンドはこの名前ごとに分かれる。**ランナーのマシンや計測条件を変えたら名前も変える** |
| `PERF_DATASET_PROFILE` | シードデータの名前（任意。記録用） |
| `PERF_RUNS` | 実行回数（既定: 3。3 回未満は quality-gate が WARN を付ける） |
| `PERF_JAVA_OPTS` | バックエンドの JVM の引数（任意。例: `-Xmx512m`） |
| `PERF_START_TIMEOUT` | 起動を待つ秒数（既定: 120） |
| `PERF_WARMUP_SECONDS` / `PERF_DURATION_SECONDS` | ウォームアップと計測の秒数（既定: 60 / 300）。**手元で試すときだけ**短くする。仕様と違う値で送った結果は判定に使わないでください |

シナリオの書き方（`collector/targets/ymiyamoto63__like-chatgpt.k6.js` を写して書き換える）:

- 計測区間のシナリオに `phase: measure` のタグを付け、ウォームアップには `phase: warmup` を付ける
- シナリオ名（`options.scenarios` のキー）を合格ライン（`*.gate.yml`）の `performance.scenarios` と一致させる。
  シナリオごとに `http_req_duration{scenario:<名前>}` のしきい値を書いておく（k6 はしきい値のあるタグ付き指標だけを出力する）
- 計測区間の到達率の合計を合格ライン（`*.gate.yml`）の `performance.arrival_rate_rps` と一致させる
- `handleSummary` で `http_reqs{phase:measure}` の rate を「件数 ÷ 計測秒数」に直して出力する。
  k6 の rate はテスト全体の時間（ウォームアップを含む）で割るため、そのままでは到達率が 5/6 に見え、M-04 が WARN になる

注意:

- **負荷をかける側（k6）とアプリは同じコンテナ（隔離しない場合は同じマシン）で動きます。** 値はこの構成での値で、本番の性能ではありません。
  前回との比較（性能の劣化の検出）に使ってください
- **データベースなど外部のサービスが要る対象は、今のしくみでは起動できません**（M-10 と同じ）。like-chatgpt はメモリ上の固定データだけで応答し、外部の API も呼びません。
  外部の有料 API を呼ぶ対象では、スタブに差し替える手段を用意するまで性能を計測しないでください
- 計測中はランナーが約 18 分ふさがります。その間の PR の計測は待たされます

## 判定結果を読むときの注意

- **M-02 と M-03〜05 は PR 以外の Run にだけ値が付きます。** PR の Run では SKIP で、部分計測になります。
  PR で下がるかどうかは、マージ後のブランチの Run で分かります
- **M-03〜05 は計測環境（`PERF_ENVIRONMENT`）ごとに比べます。** 名前を変えると前回比が出なくなり、トレンドも新しい系列になります
- **M-07 は base と比べて判定します。** CC 15 超の関数のうち、新しく増えたものや悪化したものだけが FAIL の対象です
- **M-07 には frontend の関数も入ります。** frontend の CC は quality-gate 側の ESLint の設定で数えるため、
  対象の `npm run lint` の `complexity` の警告とは版や設定の違いでずれることがあります
- **M-13（シークレット）は M-06（脆弱性）とは別に数えます。**
  合格ライン（`*.gate.yml`）で `secrets` を有効にしないと、シークレットは判定されません（like-chatgpt の `*.gate.yml` では有効にしています）
- **M-14（ライセンス）は forbidden だけが不合格**です。restricted（GPL など）と分類不明は警告にとどめます。
  使ってよいと判断したパッケージがあれば、合格ライン（`*.gate.yml`）で扱います
- **M-11 / M-12 はすべてのテスト**（`TEST_REPORTS` に合う backend のテストと、frontend の Vitest）の結果です。
  既定で有効です（合格ラインの `test_results` で無効にできます）。
  M-12 は比較対象の Run からスキップが増えたら FAIL です
- **M-09 の初回**（比較元に OpenAPI 定義が無いとき）は「対象外」になります
- 判定には**計測した時点の main の `*.gate.yml`** を使います。再評価ではその Run が送った設定で判定し直します。設定の変更履歴は S-06 の「変更履歴」と Git の履歴で追えます

## 対象リポジトリの計測用ファイル

取り込みは収集ランナーからだけで（D-19）、Ingest Token も収集ランナー用の 1 つです（D-27）。対象リポジトリの CI からは送れません。
対象リポジトリに以前の方式の計測用ファイル（`.quality-gate.yml`、`.github/workflows/quality-gate.yml`、`scripts/quality-gate-submit.sh` など）は
収集ランナーでは使いません。消すかどうかは対象側の判断です。

## 手元で試す

ワークフローと同じことを手元で実行できます（Docker と `curl` / `jq` が必要）。

```bash
WORK=/tmp/qg-collector
# private リポジトリなら GH_TOKEN に読み取り権限のあるトークンを入れる
./collector/bin/fetch.sh ymiyamoto63/like-chatgpt "$WORK"
./collector/bin/measure-isolated.sh ymiyamoto63/like-chatgpt "$WORK" "$WORK/reports"
# コンテナを使わずに直接計測するなら（JDK・Node.js・Docker が必要）
# ./collector/bin/measure.sh ymiyamoto63/like-chatgpt "$WORK" "$WORK/reports"

QG_BASE_URL=http://localhost:8080 QG_INGEST_TOKEN=<バックエンドの QG_INGEST_TOKEN> \
  ./collector/bin/submit.sh "$WORK/reports"
```

`fetch.sh` は `QG_BRANCH` / `QG_COMMIT` / `QG_PR_NUMBER` / `QG_BASE_BRANCH` / `QG_BASE` でワークフローの入力と同じ指定ができます。

PR 以外の計測では負荷試験（約 18 分）も実行されます。k6 のシナリオを手元で確かめるだけなら、
計測プロファイルを写したものに `PERF_RUNS=1`・`PERF_WARMUP_SECONDS=5`・`PERF_DURATION_SECONDS=20` を足して短く実行できます
（その結果は quality-gate に送らないでください）。

## 対象を追加する

1. `collector/targets/<owner>__<name>.env` を作る（like-chatgpt のものを写して書き換える）
2. 合格ラインとして `collector/targets/<owner>__<name>.gate.yml` を作る（無いと送信の前に止まる）
   （性能を計測するなら `collector/targets/<owner>__<name>.k6.js` も作る。[8 章](#8-m-0305性能)）
3. 1-2 の App を対象にもインストールし、quality-gate にリポジトリを登録する（1-4）。Ingest Token は共通なので足さなくてよい

スクリプトは、Maven（`BACKEND_DIR`）と npm + Vitest（`FRONTEND_DIR`）の構成だけを扱います。
使わない側は計測プロファイルで空にしてください。

## 安全上の注意

- `measure` ジョブは対象のビルド・テストのコードを**計測用のコンテナの中で**実行します（[7 章](#7-計測のコンテナ隔離)）。
  認証情報は渡していません（トークンを使うのは `fetch` と `submit` だけ）。コンテナからはランナーのマシンのファイルや Docker に触れません。
  ただし外向きの通信はできるため、自分たちが管理するリポジトリだけを対象にしてください
- Maven と npm のキャッシュは Docker のボリューム（`quality-gate-collector-home`）に残り、次の計測でも使われます。
  キャッシュは対象の間で共有されるため、対象を増やすときは対象ごとにボリュームを分けることを検討します
- 取得したソースは `measure` ジョブの最後に削除します。ジョブ間の受け渡し用の成果物（`collector-source`）は 1 日で消えます
- フォークからの PR（リポジトリに書き込み権限の無い人のコード）は計測しないでください。計測はセルフホストランナーで動きます

## うまくいかないとき

| 症状 | 主な原因と対処 |
| --- | --- |
| `fetch` が `計測プロファイルがありません` | `collector/targets/` にそのリポジトリの `.env` が無い、または repository の綴りが違う |
| `fetch` が `could not read Username` / `Repository not found` | App が対象にインストールされていない、Contents の権限が無い、または `QG_COLLECTOR_APP_ID` が未設定で private を取得しようとした |
| `measure` で `バックエンドのビルドに失敗しました` | 対象がコンパイルできない、または `JAVA_VERSION` が対象の要求と合っていない |
| `measure` で `M-02: PIT の実行に失敗しました` | テストに失敗がある（PIT は全テストが成功していないと動かない）、テストが JUnit 5 でない、または `MUTATION_TARGET_CLASSES` に合うクラスが無い（`No mutations found`） |
| `measure` で `lcov.info がありません` | `FRONTEND_COVERAGE_INCLUDE` のパターンが一致していない（空白区切りで書く） |
| `measure` で `M-07: ESLint（head）の実行に失敗しました` | `FRONTEND_COMPLEXITY_SOURCES` のディレクトリが無い、または ESLint が設定を読めなかった（ログに ESLint の出力が出る） |
| `measure` で `M-07: 構文を読めず、関数を数えられなかったファイルがあります` | quality-gate 側のパーサが読めない構文のファイルがある（そのファイルの関数は M-07 に入らない）。`FRONTEND_COMPLEXITY_EXCLUDE` で外すか、`collector/complexity` のパーサを見直す |
| `measure` で `M-11/M-12: バックエンドのテストの結果がありません` | `TEST_REPORTS` のパターンが一致していない（`BACKEND_DIR/target` からの相対で、空白区切りで書く） |
| `submit` が `QG_BASE_URL（Variables）または QG_INGEST_TOKEN（Secrets）が未設定です` | 1-3 の設定漏れ |
| `submit` の Run 作成が 401 | 収集ランナーの `QG_INGEST_TOKEN` とバックエンドの `QG_INGEST_TOKEN` が一致していない |
| `submit` の Run 作成が 404 | quality-gate にリポジトリを登録していない（1-4） |
| `submit` が `判定に失敗しました（CONFIG_VALIDATION_FAILED）` | 合格ライン（`*.gate.yml`）の誤り。Run 詳細に行番号つきの理由が出る |
| PR の Run で M-02 / M-03〜05 が ERROR（スキップが許容されていない） | 合格ライン（`*.gate.yml`）の `execution.skippable_metrics` に `mutation_score` と `performance` が必要 |
| M-03 が ERROR（シナリオがありません） | k6 のシナリオ名と合格ライン（`*.gate.yml`）の `performance.scenarios` が一致していない |
| M-04 が WARN（到達率が設定値の 95% 未満） | アプリが負荷を捌けていない、`handleSummary` で rate を直していない、または到達率の合計と `arrival_rate_rps` が一致していない |
| `measure` で `M-03〜05: バックエンドが起動しませんでした` | ポート（`PERF_BACKEND_PORT`）が使われている、または起動に外部のサービスが要る |
| `measure` で `M-03〜05: k6 を取得できませんでした` | github.com に届かない |
| `measure` で `M-10: バックエンドが起動しませんでした` / `フロントエンドが起動しませんでした` | ポートが使われている、起動に外部のサービスが要る、または起動が `A11Y_START_TIMEOUT` 秒に収まらない。ログにアプリの出力の末尾が出る |
| `measure` で `docker がありません` / `permission denied ... docker.sock` | ランナーに Docker が無い、またはランナーの利用者が `docker` グループに入っていない（1-1） |
| `measure` の `計測用のコンテナの作成` で失敗する | Docker Hub・nodejs.org・github.com・archive.apache.org に届かない。社内のミラーを使うなら `QG_COLLECTOR_BASE_IMAGE` を指定する |
| `measure` で `Node.js の版を解決できませんでした` | 対象の `.nvmrc` の書き方が解釈できない（`22` / `v22.21.1` / `lts/*` の形に対応）、または nodejs.org に届かない |
| `measure` で `M-10: 検査ツールを用意できませんでした` | npm レジストリに届かない、または Chromium の取得に失敗した（1-1） |
| M-10 が ERROR（検査した画面が足りない） | `A11Y_PAGES` と合格ライン（`*.gate.yml`）の `accessibility.pages` がずれている、画面を読み込めなかった、または `A11Y_READY_SELECTOR` の要素が現れない |
| Chromium が `error while loading shared libraries` で起動しない | ランナーに Chromium のライブラリが無い。`sudo npx playwright install-deps chromium` を一度実行する |
| PR の Run の M-02 が ERROR（スキップの申告が受け付けられない） | 合格ライン（`*.gate.yml`）の `execution.skippable_metrics` に `mutation_score` が無い（1-4） |
| 実行してもジョブが始まらない | ランナーが止まっている（[セルフホストランナーの運用](self-hosted-runner.md)） |
| submit で `合格ラインがありません` | `collector/targets/<owner>__<name>.gate.yml` が無い（[対象を追加する](#対象を追加する)） |
