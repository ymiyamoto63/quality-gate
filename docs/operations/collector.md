# 収集ランナーで計測する

対象リポジトリに**何も置かずに**、quality-gate 側で対象を取得・計測して取り込む手順です。
これが対象リポジトリを計測する標準の方法です（[決定事項 D-16](../initial/03-open-questions.md)）。
方式の考え方と移行計画は [収集ランナー方式](../architecture/collector-runner.md)、
しくみの全体像は [はじめての人向け: quality-gate のしくみ](../architecture/overview-for-beginners.md) を参照してください。

計測する指標は **M-01 / M-06 / M-07 / M-08 / M-09** です。
M-02（PIT）・M-10（アクセシビリティ）・M-03〜05（性能）はまだ計測しません。

| 段階 | 状態 | 内容 |
| --- | --- | --- |
| 1 | 実装済み | 手動実行（`workflow_dispatch`）で 1 コミットを計測する |
| 2 | 実装済み | **15 分ごとの定期実行**で、既定ブランチと PR の先頭のうち未計測のコミットを計測する（[4. 定期実行](#4-定期実行段階-2)） |

## 構成

| ファイル | 役割 |
| --- | --- |
| `.github/workflows/collect.yml` | 入り口。定期実行と手動実行を受け付け、`plan` ジョブで計測する対象を決めて、対象ごとに `collect-target.yml` を呼ぶ |
| `.github/workflows/collect-target.yml` | 1 コミットの計測。`fetch`（取得）→ `measure`（計測）→ `submit`（送信）の 3 ジョブ。すべてセルフホストランナーで動く |
| `collector/bin/detect.sh` | 定期実行で、未計測の先頭コミット（既定ブランチと PR）を探す |
| `collector/bin/state.sh` | 計測済みの記録（ランナーのマシン上のファイル） |
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

1. **管理 › リポジトリ管理（S-08）** で like-chatgpt を登録し、Ingest Token を発行する（1-3 のシークレットに入れる）。
   対象の CI 用のトークンがすでにある場合も、収集ランナー用に別のトークンを発行する（あとで CI 用だけを失効させられるように）
2. like-chatgpt の **設定（S-06）** に `collector/targets/ymiyamoto63__like-chatgpt.gate.yml` の内容を貼り付けて保存する

2 を忘れると既定値（全指標が有効）で判定され、まだ計測しない M-02 / M-10 などが ERROR になって Run 全体が FAIL になります。

対象の CI から `.quality-gate.yml` 付きの Run が届いていたリポジトリでは、**直近の Run がファイルの設定で判定されている間は S-06 から保存できません**
（「直近の Run がファイルの設定で判定されているため、画面からは編集できません」）。
収集ランナーで 1 回計測すると（既定値で判定されて FAIL になります）保存できるようになるので、
保存した後にその Run を **Run 詳細 › 再評価** で判定し直してください。
対象の CI がまだ送信を続けていると、そちらの Run が届くたびにまた編集できなくなります（[対象の CI からの送信を止める](#対象の-ci-からの送信を止める)）。

## 2. 手動で実行する

定期実行（[4](#4-定期実行段階-2)）とは別に、いつでも手動で計測できます。計測済みのコミットでも計測し直します。

1. quality-gate の **Actions → collect → Run workflow** を開く
2. 入力して実行する

   | 入力 | 例 | 説明 |
   | --- | --- | --- |
   | repository | `ymiyamoto63/like-chatgpt` | 計測プロファイルがあるリポジトリ |
   | branch | （空） | 空なら既定ブランチ。PR の場合は PR のブランチ名 |
   | commit | （空） | 特定のコミットを測るときだけ 40 桁で指定する |
   | pull_request | （空） | PR を計測するときの番号 |
   | base_branch | （空） | 比較元を決めるブランチ。空なら既定ブランチ。PR のマージ先が既定ブランチ以外のときに指定する |

3. 実行画面に `plan` と、対象ごとのまとまり（例: `like-chatgpt main`）ができる。その中の `submit` ジョブのログに `Run を作成しました: <runId>` と送信したファイルが出ていることを確かめる
4. quality-gate の Run 詳細で結果を見る。収集ランナーの Run は `triggeredBy` が `collector` になる

`measure` ジョブの成果物 `collector-reports`（7 日保持）で、送った内容をあとから確認できます。

## 3. 従来の方式と結果が一致するかを確かめる（段階 1 の完了条件）

同じコミットについて、like-chatgpt の CI（対象に計測用ファイルを置く従来の方式）と収集ランナーの結果を比べます。
Ingest API は同一コミットへの再送信を別の Run（attempt を増やす）として受け付けるため、両方の Run が残ります。

| 指標 | 一致すべきもの |
| --- | --- |
| M-01 | backend / frontend それぞれのブランチカバレッジ |
| M-06 | 件数（Trivy の版の違いで差が出うる。差が出たら `versions.env` の版を揃えて確かめる） |
| M-07 | **一致しないのが正しい**。従来の方式は base を送らないため「ベース比較不可」になり、収集ランナーは base 比較で判定する |
| M-08 | 契約テストの件数と成功率 |
| M-09 | 破壊的変更の件数（または「対象外」） |

参考までに、like-chatgpt の `b581260`（main）を手元で計測した結果は、JaCoCo・lcov・PMD の各数値と
契約テストの件数が、like-chatgpt 自身のビルド（`mvn verify` / `npm run test:coverage`）の出力と一致しました。

## 4. 定期実行（段階 2）

### 4-1. しくみ

`collect` ワークフローは 15 分ごと（`*/15 * * * *`）に起動し、次のことを行います。

1. `plan` ジョブが、計測プロファイルで `SCHEDULE=true` にした対象について、GitHub API で次の先頭コミットを調べる
   - 既定ブランチ（`DEFAULT_BRANCH`）の先頭
   - open な PR の先頭（`MEASURE_PULL_REQUESTS=true` のとき。**同じリポジトリのブランチから出ている PR だけ**。フォークからの PR は計測しない）
2. そのうち**計測済みの記録に無いもの**を、1 回につき最大 5 件選ぶ（残りは次回に回る）
3. 選んだコミットを 1 件ずつ `collect-target.yml` で計測する
4. 送信まで終わったら「計測済み」として記録する。失敗したら「失敗」として記録し、**3 回失敗したコミットは定期実行では計測しない**

比較元（base）は、既定ブランチなら直前のコミット、PR なら**マージ先のブランチ**との merge-base です。
同じコミットでも、既定ブランチとしての計測と PR としての計測は比較元が違うため、別々に計測します。

GitHub の定期実行は混雑すると数分〜十数分遅れることがあります。push から計測開始までは、おおむね 15〜30 分と考えてください。

### 4-2. 事前の準備（段階 1 からの追加分）

| 準備 | 内容 |
| --- | --- |
| 計測プロファイル | `SCHEDULE=true` と `MEASURE_PULL_REQUESTS=true` を書く（like-chatgpt は設定済み） |
| GitHub App | 段階 1 の **Pull requests: Read-only** が必要（PR の一覧を読むため）。App は quality-gate と同じ owner（`ymiyamoto63`）のアカウントにインストールされていること |
| ワークフローが main にあること | 定期実行は**既定ブランチ（main）にあるワークフローだけ**が動く |

定期実行の `plan` ジョブは、quality-gate リポジトリの owner のインストールに対するトークンで API を読みます。
**owner が違う対象（別アカウントのリポジトリ）は定期実行では飛ばします**（手動実行なら計測できます）。

1 回で計測する件数の上限は、リポジトリ変数 `QG_COLLECTOR_MAX_PER_RUN`（既定 5）で変えられます。

### 4-3. 重複と取りこぼしを防ぐしくみ

| 状況 | 動き |
| --- | --- |
| 前回の定期実行がまだ動いているのに次の回が来た | 次の回は待機する（`concurrency`）。待機は 1 つだけ残り、前回の記録を見てから対象を選ぶので二重に計測しない |
| 計測中にランナーが止まった・取り消した | 記録しない。次の回で計測し直す |
| quality-gate が止まっていて送信に失敗した | 「失敗」として記録し、次の回で計測し直す（3 回まで） |
| 対象のビルドが壊れている | 送信までは進む（該当の指標が ERROR の Run ができる）。「計測済み」として記録し、再計測しない |
| 1 回の上限を超える未計測のコミットがある | 上限を超えた分は次の回に回る |

手動実行は定期実行と独立して動きます。手動実行で計測したコミットも「計測済み」として記録され、定期実行では計測し直しません。

### 4-4. 計測済みの記録

記録は**ランナーのマシン上のファイル**です（ランナーを動かしているユーザーの `~/.local/state/quality-gate-collector/measured.tsv`）。
1 行 1 件で「キー、結果（`ok` / `failed`）、日時」が追記されます。

```bash
# 記録を見る（ランナーのマシンで）
sudo -iu runner cat ~/.local/state/quality-gate-collector/measured.tsv

# 3 回失敗して諦めたコミットを、定期実行でもう一度計測させる（そのキーの行を消す）
sudo -iu runner sed -i '/pr:14 c643edd24a5441dc2d7063a7394f51a9127fa472/d' ~/.local/state/quality-gate-collector/measured.tsv
```

ファイルを消しても、各ブランチ・PR の**いまの先頭**を 1 回ずつ計測し直すだけです（過去のコミットをさかのぼって計測することはありません）。
ランナーを別のマシンに移したときも同じです。

### 4-5. 定期実行を止める

| 止めたい範囲 | 方法 |
| --- | --- |
| 特定の対象だけ | 計測プロファイルの `SCHEDULE` を `false` にする |
| PR の計測だけ | 計測プロファイルの `MEASURE_PULL_REQUESTS` を `false` にする |
| 定期実行すべて（一時的に） | quality-gate の **Actions → collect → ︙ → Disable workflow**。手動実行もできなくなるので、再開するときは **Enable workflow** |

## 判定結果を読むときの注意

- **M-02（PIT）・M-10（アクセシビリティ）・M-03〜05（性能）はまだ計測しません**（段階 3・4 で追加予定）。
  画面の設定（1-4）で無効にしておかないと、成果物が無いため ERROR になります
- **M-07 は base と比べて判定します。** CC 15 超の関数のうち、新しく増えたものや悪化したものだけが FAIL の対象です
- **M-08 は計測プロファイルの `CONTRACT_TEST_REPORTS` に合うテストの成功率**です。
  like-chatgpt には Pact などの契約テストが無いため、MockMvc で API を検証する `*ControllerTest` を契約テストとして扱っています
- **M-09 の初回**（比較元に OpenAPI 定義が無いとき）は「対象外」になります
- 判定には**画面（S-06）で保存した最新の設定**を使います。コミット時点の設定ではありません。設定の変更履歴は S-06 の「変更履歴」と監査ログで追えます

## 対象の CI からの送信を止める

収集ランナーだけで計測できるようになったら、対象リポジトリの CI から quality-gate への送信は不要です。
両方から送ると、同じコミットの Run が 2 つでき、対象の CI が送る `.quality-gate.yml` が画面の設定より優先されます。

対象リポジトリに手を入れずに止めるには、**quality-gate 側で対象の CI 用の Ingest Token を失効させます**（S-08）。
収集ランナー用のトークンを別に発行しておけば、収集ランナーの送信には影響しません。
対象リポジトリの計測用ファイル（`.quality-gate.yml`、`.github/workflows/quality-gate.yml`、`scripts/quality-gate-submit.sh` など）は
収集ランナーでは使いません。消すかどうかは対象側の判断です（M-02 / M-10 を収集ランナーへ移すまでは、それらの計測に対象の CI を使い続ける選択もあります）。

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

`fetch.sh` は `QG_BRANCH` / `QG_COMMIT` / `QG_PR_NUMBER` / `QG_BASE_BRANCH` でワークフローの入力と同じ指定ができます。

定期実行が選ぶ対象は、次のように確かめられます（計測はしません）。

```bash
GH_TOKEN=<読み取り権限のあるトークン> ./collector/bin/detect.sh
```

## 対象を追加する

1. `collector/targets/<owner>__<name>.env` を作る（like-chatgpt のものを写して書き換える）。定期実行するなら `SCHEDULE=true`
2. 画面の設定の控えとして `collector/targets/<owner>__<name>.gate.yml` を作り、S-06 に保存する
3. 1-2 の App を対象にもインストールし、1-3 に Ingest Token のシークレットを足す

スクリプトは、Maven（`BACKEND_DIR`）と npm + Vitest（`FRONTEND_DIR`）の構成だけを扱います。
使わない側は計測プロファイルで空にしてください。

## 安全上の注意

- `measure` ジョブは**対象のビルド・テストのコードをセルフホストランナー上で直接実行**します。
  認証情報は渡していませんが（トークンを使うのは `fetch` と `submit` だけ）、ランナーのマシン自体は対象のコードから触れます。
  自分たちが管理するリポジトリだけを対象にしてください
- Maven と npm のキャッシュ（`~/.m2`、`~/.npm`）はランナーに残り、次の計測でも使われます
- 取得したソースは `measure` ジョブの最後に削除します。ジョブ間の受け渡し用の成果物（`collector-source`）は 1 日で消えます
- 計測はコンテナに隔離していません。隔離は対象を増やす前に検討します
- 定期実行で PR を計測するのは、同じリポジトリのブランチから出ている PR だけです。フォークからの PR（リポジトリに書き込み権限の無い人のコード）はセルフホストランナーで実行しません

## うまくいかないとき

| 症状 | 主な原因と対処 |
| --- | --- |
| `fetch` が `計測プロファイルがありません` | `collector/targets/` にそのリポジトリの `.env` が無い、または repository の綴りが違う |
| `fetch` が `could not read Username` / `Repository not found` | App が対象にインストールされていない、Contents の権限が無い、または `QG_COLLECTOR_APP_ID` が未設定で private を取得しようとした |
| `measure` で `バックエンドのビルドに失敗しました` | 対象がコンパイルできない、または `JAVA_VERSION` が対象の要求と合っていない |
| `measure` で `lcov.info がありません` | `FRONTEND_COVERAGE_INCLUDE` のパターンが一致していない（空白区切りで書く） |
| `submit` が `QG_BASE_URL（Variables）または ... が未設定です` | 1-3 の設定漏れ。Ingest Token のシークレット名は計測プロファイルの `INGEST_TOKEN_SECRET` と一致させる |
| M-02 / M-10 が ERROR で Run 全体が FAIL | 1-4 の 2（画面の設定の保存）をしていない |
| 定期実行の `plan` で `PR の一覧を取得できませんでした` | App に Pull requests の読み取り権限が無い、または権限の追加をインストール先で承認していない |
| 定期実行の `plan` で `トークンの owner（…）と違うため飛ばします` | 対象の owner が quality-gate の owner と違う。定期実行の対象外（手動実行で計測する） |
| 定期実行の `plan` で `3 回失敗しているため計測しません` | そのコミットの計測が 3 回失敗した。原因を直して手動実行するか、4-4 の手順で記録を消す |
| 定期実行が動かない | ワークフローが main に無い、Disable されている、またはランナーが止まっている（`plan` もセルフホストランナーで動く） |
| S-06 で「直近の Run がファイルの設定で判定されているため、画面からは編集できません」 | 対象の CI が `.quality-gate.yml` を送っている。収集ランナーで 1 回計測してから保存する（1-4）。対象の CI からの送信を止める |
