# 収集ランナーで計測する（段階 1）

対象リポジトリに**何も置かずに**、quality-gate 側で対象を取得・計測して取り込む手順です。
方式の検討と移行計画は [収集ランナー方式への変更の検討](../architecture/collector-runner.md) を参照してください。

段階 1 で計測する指標は **M-01 / M-06 / M-07 / M-08 / M-09** です。
M-02（PIT）・M-10（アクセシビリティ）・M-03〜05（性能）はまだ計測しません。
実行は手動（`workflow_dispatch`）のみです。

## 構成

| ファイル | 役割 |
| --- | --- |
| `.github/workflows/collect.yml` | `fetch`（取得）→ `measure`（計測）→ `submit`（送信）の 3 ジョブ。すべてセルフホストランナーで動く |
| `collector/bin/fetch.sh` | 対象を clone し、計測するコミットと比較元（base）を決めて `meta.env` に書く |
| `collector/bin/measure.sh` | 計測して成果物を `reports/` にまとめる。**認証情報を受け取らない** |
| `collector/bin/submit.sh` | Ingest API に送る。`.quality-gate.yml` は送らない（判定は画面の設定で行う） |
| `collector/versions.env` | ツールの版（JaCoCo / PMD / oasdiff / Trivy）。対象の設定に関係なくこの版で計測する |
| `collector/pmd-ruleset.xml` | M-07 のルールセット（全メソッドの CC を出力する） |
| `collector/targets/<owner>__<name>.env` | 計測プロファイル（どう測るか） |
| `collector/targets/<owner>__<name>.gate.yml` | 画面（S-06）に保存する合格ラインの控え |

### 指標ごとの計測方法

| 指標 | 方法 |
| --- | --- |
| M-01（Java） | `mvn org.jacoco:jacoco-maven-plugin:<版>:prepare-agent verify ...:report`。JaCoCo をコマンドラインから差し込む。単体テストと結合テスト（failsafe）の両方を 1 つの実行データに集める |
| M-01（TS） | `vitest run` にカバレッジのオプションを渡す。`@vitest/coverage-v8` が対象に無ければ、Vitest と同じ版を作業用の clone にだけ入れる（`npm install --no-save`） |
| M-06 | コミット時点の作業ツリーに依存関係を取得した後で `trivy fs`（対象の CI と同じ順序） |
| M-07 | PMD のコマンドライン版で `src/main/java` を解析する。**head と base の両方**を解析し、base は `scope=base` で送る |
| M-08 | `mvn verify` が出す JUnit XML のうち、計測プロファイルの `CONTRACT_TEST_REPORTS` に合うものだけを送る |
| M-09 | コミットされている OpenAPI 定義を head と base で取り出し、oasdiff で比べる。base に定義が無ければ「新規 API」として送る |

テストが失敗しても計測は止めません（失敗は M-08 などの判定材料として送ります）。
ビルド自体に失敗した場合など、成果物が出なかった指標は送られず、quality-gate では ERROR になります。

比較元（base）の決め方は対象の CI と同じです。既定ブランチを計測するときは直前のコミット、
それ以外のブランチや PR を計測するときは既定ブランチとの merge-base です。

## 1. 事前の準備

### 1-1. セルフホストランナー

quality-gate の既存のセルフホストランナー（[セルフホストランナー](self-hosted-runner.md)）をそのまま使います。
追加で必要なのは次のものです。

| 必要なもの | 使う箇所 |
| --- | --- |
| `curl` / `unzip` / `jq` | PMD の取得、送信 |
| Docker Hub への外向き通信 | `tufin/oasdiff` / `aquasec/trivy` のイメージ |
| github.com への外向き通信 | 対象の clone、PMD の取得（GitHub Releases） |
| quality-gate への到達性 | `submit` ジョブもセルフホストランナーで動く |

ランナーは 1 台なので、収集ジョブと性能計測のジョブは同時には動きません（D-7 の「同居させない」を満たす）。

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
| Secrets | `QG_INGEST_TOKEN_LIKE_CHATGPT` | like-chatgpt の Ingest Token（名前は計測プロファイルの `INGEST_TOKEN_SECRET`） |

**quality-gate リポジトリは private のままにしてください。** 収集ワークフローのログと成果物には、対象のパスやテスト出力が含まれます。

### 1-4. quality-gate にリポジトリと合格ラインを登録する

1. **管理 › リポジトリ管理（S-08）** で like-chatgpt を登録し、Ingest Token を発行する（1-3 のシークレットに入れる）
2. like-chatgpt の **設定（S-06）** に `collector/targets/ymiyamoto63__like-chatgpt.gate.yml` の内容を貼り付けて保存する

2 を忘れると既定値（全指標が有効）で判定され、段階 1 で計測しない M-02 / M-10 などが ERROR になって Run 全体が FAIL になります。

## 2. 実行する

1. quality-gate の **Actions → collect → Run workflow** を開く
2. 入力して実行する

   | 入力 | 例 | 説明 |
   | --- | --- | --- |
   | repository | `ymiyamoto63/like-chatgpt` | 計測プロファイルがあるリポジトリ |
   | branch | （空） | 空なら既定ブランチ。PR の場合は PR のブランチ名 |
   | commit | （空） | 特定のコミットを測るときだけ 40 桁で指定する |
   | pull_request | （空） | PR を計測するときの番号 |

3. `submit` ジョブのログに `Run を作成しました: <runId>` と送信したファイルが出ていることを確かめる
4. quality-gate の Run 詳細で結果を見る。収集ランナーの Run は `triggeredBy` が `collector` になる

`measure` ジョブの成果物 `collector-reports`（7 日保持）で、送った内容をあとから確認できます。

## 3. 現在の方式と結果が一致するかを確かめる（段階 1 の完了条件）

同じコミットについて、like-chatgpt の CI（現在の方式）と収集ランナーの結果を比べます。
Ingest API は同一コミットへの再送信を別の Run（attempt を増やす）として受け付けるため、両方の Run が残ります。

| 指標 | 一致すべきもの |
| --- | --- |
| M-01 | backend / frontend それぞれのブランチカバレッジ |
| M-06 | 件数（Trivy の版の違いで差が出うる。差が出たら `versions.env` の版を揃えて確かめる） |
| M-07 | **一致しないのが正しい**。現在の方式は base を送らないため「ベース比較不可」になり、収集ランナーは base 比較で判定する |
| M-08 | 契約テストの件数と成功率 |
| M-09 | 破壊的変更の件数（または「対象外」） |

参考までに、like-chatgpt の `b581260`（main）を手元で計測した結果は、JaCoCo・lcov・PMD の各数値と
契約テストの件数が、like-chatgpt 自身のビルド（`mvn verify` / `npm run test:coverage`）の出力と一致しました。

## 手元で試す

ワークフローと同じことを手元で実行できます（Docker・JDK・Node.js が必要）。

```bash
WORK=/tmp/qg-collector
# private リポジトリなら GH_TOKEN に読み取り権限のあるトークンを入れる
./collector/bin/fetch.sh ymiyamoto63/like-chatgpt "$WORK"
./collector/bin/measure.sh ymiyamoto63/like-chatgpt "$WORK" "$WORK/reports"

QG_BASE_URL=http://localhost:8080 QG_INGEST_TOKEN=qg_xxxxxxxx_xxxxxxxx \
  ./collector/bin/submit.sh "$WORK/reports"
```

`fetch.sh` は `QG_BRANCH` / `QG_COMMIT` / `QG_PR_NUMBER` でワークフローの入力と同じ指定ができます。

## 対象を追加する

1. `collector/targets/<owner>__<name>.env` を作る（like-chatgpt のものを写して書き換える）
2. 画面の設定の控えとして `collector/targets/<owner>__<name>.gate.yml` を作り、S-06 に保存する
3. 1-2 の App を対象にもインストールし、1-3 に Ingest Token のシークレットを足す

段階 1 のスクリプトは、Maven（`BACKEND_DIR`）と npm + Vitest（`FRONTEND_DIR`）の構成だけを扱います。
使わない側は計測プロファイルで空にしてください。

## 安全上の注意

- `measure` ジョブは**対象のビルド・テストのコードをセルフホストランナー上で直接実行**します。
  認証情報は渡していませんが（トークンを使うのは `fetch` と `submit` だけ）、ランナーのマシン自体は対象のコードから触れます。
  自分たちが管理するリポジトリだけを対象にしてください
- Maven と npm のキャッシュ（`~/.m2`、`~/.npm`）はランナーに残り、次の計測でも使われます
- 取得したソースは `measure` ジョブの最後に削除します。ジョブ間の受け渡し用の成果物（`collector-source`）は 1 日で消えます
- 段階 1 では計測をコンテナに隔離していません。隔離は対象を増やす前に検討します

## うまくいかないとき

| 症状 | 主な原因と対処 |
| --- | --- |
| `fetch` が `計測プロファイルがありません` | `collector/targets/` にそのリポジトリの `.env` が無い、または repository の綴りが違う |
| `fetch` が `could not read Username` / `Repository not found` | App が対象にインストールされていない、Contents の権限が無い、または `QG_COLLECTOR_APP_ID` が未設定で private を取得しようとした |
| `measure` で `バックエンドのビルドに失敗しました` | 対象がコンパイルできない、または `JAVA_VERSION` が対象の要求と合っていない |
| `measure` で `lcov.info がありません` | `FRONTEND_COVERAGE_INCLUDE` のパターンが一致していない（空白区切りで書く） |
| `submit` が `QG_BASE_URL（Variables）または ... が未設定です` | 1-3 の設定漏れ。Ingest Token のシークレット名は計測プロファイルの `INGEST_TOKEN_SECRET` と一致させる |
| M-02 / M-10 が ERROR で Run 全体が FAIL | 1-4 の 2（画面の設定の保存）をしていない |
