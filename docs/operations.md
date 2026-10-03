# 運用: 環境設定と計測の実行

quality-gate のアプリを動かし、収集ランナーで対象リポジトリを計測するまでの手順です。
仕組みと考え方は [アーキテクチャ](architecture.md#3-測定の仕組み収集ランナー)、指標の定義は [指標](metrics.md) を参照してください。

## 1. 全体の流れ

初めて使うときは次の順に準備します。

| # | やること | 場所 | 節 |
| --- | --- | --- | --- |
| 1 | アプリを起動し、計測対象・ログインのパスワード・Ingest Token・合格ラインを環境変数で設定する | アプリを動かすマシン | [2](#2-アプリを動かす) |
| 2 | セルフホストランナーを登録する | ランナーのマシン | [3](#3-セルフホストランナーを準備する) |
| 3 | 対象を読む GitHub App を作り、対象にインストールする（private のときだけ） | GitHub | [3.3](#33-対象を読むための-github-app) |
| 4 | quality-gate リポジトリに変数とシークレットを設定する | GitHub | [3.4](#34-リポジトリの変数とシークレット) |
| 5 | 計測プロファイルを書く | quality-gate リポジトリ | [4](#4-計測対象を設定する) |
| 6 | collect ワークフローを手動実行する | GitHub Actions | [5](#5-計測を実行する) |

対象リポジトリには何も置きません。quality-gate が見るアプリは 1 つだけで、アプリの `QG_REPOSITORY` と計測プロファイルの `QG_REPOSITORY` に同じリポジトリを書きます。

## 2. アプリを動かす

### 2.1 起動

本番に近い形では、DB とアプリの両方をコンテナで動かします（JDK / Node.js は不要です）。

```bash
cp .env.example .env        # 値を書き入れる（2.2）
docker compose --profile full up -d --build     # http://localhost:8080
```

- `Dockerfile` がマルチステージビルドでフロントエンドを同梱した jar を作り、JRE だけのイメージで起動します
- `app` は `db` のヘルスチェックが通ってから起動し、自身のヘルスチェックは `/actuator/health` が `UP` を返すかで判定します
- 成果物は名前付きボリューム `artifacts`（コンテナ内の `/var/lib/quality-gate/artifacts`）、DB は `pgdata` に残ります。消すときは `docker compose down -v`
- jar だけで動かす場合は `cd backend && ./mvnw package -DskipTests` で `target/quality-gate.jar` を作り、`java -jar` で起動します

DB は日次でバックアップしてください。

### 2.2 設定値

アプリは環境変数から設定を読みます。開発時はリポジトリ直下の `.env` も読みます（優先順位は **環境変数 > `.env` > 既定値**）。

| 変数 | 既定値 | 説明 |
| --- | --- | --- |
| `QG_REPOSITORY` | （必須） | 計測対象のリポジトリ（`owner/name`）。これと違うリポジトリの計測は取り込みで拒否します |
| `QG_LOGIN_USERNAME` / `QG_LOGIN_PASSWORD` | `quality` / （必須） | 画面のログイン。全員で共有する 1 つのアカウントです。パスワードは 12 文字以上（例: `openssl rand -base64 18`）。空のままでは起動しません |
| `QG_DB_URL` / `QG_DB_USERNAME` / `QG_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/qualitygate` / `qualitygate` / `qualitygate` | 接続先 DB。`compose.yaml` の `db` と一致しています |
| `QG_ARTIFACT_ROOT` | `./data/artifacts` | 成果物の保存先（相対パスは起動したディレクトリから） |
| `QG_INGEST_TOKEN` | なし | Ingest Token（2.3）。未設定なら取り込み API はすべて 401 |
| `QG_BASE_URL` | `http://localhost:8080` | 取り込み API の応答に載せるリリース判定の URL の組み立てに使う |
| `QG_*`（合格ライン） | [指標](metrics.md) の既定値 | 2.4 |
| `QG_LOG_FORMAT` | なし（テキスト） | `ecs` / `logstash` / `gelf` で 1 行 1 JSON のログにします（`requestId` / `runId` が項目として載る）。`compose.yaml` の `app` では `ecs` |

- `docker compose --profile full` の `app` コンテナには `.env` の値がすべて渡ります。DB の接続先・`QG_ARTIFACT_ROOT`・`QG_LOG_FORMAT` はコンテナ用の値で上書きされます
- データは自動では消しません（計測は手動で件数が少ないため）

### 2.3 Ingest Token

収集ランナーが計測結果を送るための鍵です（収集ランナー用の 1 つだけ）。アプリの `QG_INGEST_TOKEN` と、quality-gate リポジトリの Secret `QG_INGEST_TOKEN`（3.4）に同じ値を入れます。

```bash
openssl rand -hex 32
```

交換するときは、送信を止めないよう次の順で行います（漏えいしたときも同じ手順です）。

1. アプリの `QG_INGEST_TOKEN` を `<古い値>,<新しい値>` にして再起動する（両方を受け付ける）
2. quality-gate リポジトリの Secret `QG_INGEST_TOKEN` を新しい値にする
3. アプリの `QG_INGEST_TOKEN` を新しい値だけにして再起動する

### 2.4 合格ライン

合格ラインは環境変数で持ちます（画面やプルリクエストでは変えません）。書かなかった項目は [指標](metrics.md) の既定値になります。

| 変数 | 既定値 | 指標 |
| --- | --- | --- |
| `QG_DISABLED_METRICS` | 空 | 判定しない指標 ID（カンマ区切り。例: `M-03,M-04`）。計測プロファイルの `DISABLED_METRICS` とそろえる |
| `QG_EXCLUSIONS` | 空 | 計測から除くファイル（glob、カンマ区切り。例: `**/Main.java,**/*.d.ts`） |
| `QG_BRANCH_COVERAGE_MIN` | `75` | M-01 ブランチカバレッジの下限（%） |
| `QG_MUTATION_SCORE_MIN` / `QG_MUTATION_COMPONENTS` | `60` / 空 | M-02 の下限（%）/ 対象のコンポーネント（例: `backend`。空なら限定しない） |
| `QG_RESPONSE_TIME_P95_MAX_MS` / `QG_ARRIVAL_RATE_RPS` / `QG_PERF_SCENARIOS` | `500` / `50` / 空 | M-03 の上限（ms）/ 負荷条件（req/s）/ 結果が必要な k6 のシナリオ |
| `QG_ERROR_RATE_MAX_PCT` | `0.1` | M-04 エラー率の上限（%） |
| `QG_CRITICAL_VULNERABILITIES_MAX` / `QG_HIGH_VULNERABILITIES_MAX` | `0` / `0` | M-05 |
| `QG_COMPLEXITY_MAX` | `15` | M-06 関数の循環的複雑度の上限 |
| `QG_BREAKING_CHANGES_MAX` | `0` | M-07 |
| `QG_ACCESSIBILITY_VIOLATIONS_MAX` / `QG_ACCESSIBILITY_PAGES` | `0` / 空 | M-08 の上限（基準は WCAG 2.2 AA に固定）/ 検査されているべき画面（カンマ区切り。計測プロファイルの `A11Y_PAGES` とそろえる） |
| `QG_TEST_SUCCESS_RATE_MIN` / `QG_TEST_COUNT_MIN` | `100` / `1` | M-09 |
| `QG_SKIPPED_TESTS_INCREASE_MAX` | `0` | M-10 前回からの増加の上限 |
| `QG_SECRETS_MAX` | `0` | M-11 |
| `QG_FORBIDDEN_LICENSES_MAX` | `0` | M-12 |

- 変えた値はアプリを再起動した後の判定から効きます。**判定済みの計測の結論は変わりません**（判定に使った合格ラインは計測ごとに残り、画面の「合格ライン」列に出ます）
- 合格ラインを緩めたときは、理由を記録してください（デプロイの設定の変更履歴や、関係者への報告）
- 値の形式が不正（数でない、知らない指標 ID など）だと起動に失敗します

### 2.5 ログイン

画面を開くとログインを求められます。`QG_LOGIN_USERNAME` / `QG_LOGIN_PASSWORD` を見る人に伝えてください。ロールは無く、全員が同じリリース判定を見ます。
パスワードを変えるときは `QG_LOGIN_PASSWORD` を変えて再起動します（ログイン中の人もログインし直しになります）。

## 3. セルフホストランナーを準備する

収集ランナー（`collect.yml` の `fetch` / `measure` / `submit` のすべて）はセルフホストランナー（`runs-on: self-hosted`）で動きます。
PR の CI（`ci.yml`）は GitHub ホストランナーで動くため、ランナーが止まっていても PR の CI は止まりません。

### 3.1 マシン

Linux x64 を想定します（WSL2 でも可）。JDK・Node.js・Chromium などは計測用のコンテナに入るため、マシンに入れる必要はありません。

| 必要なもの | 使う箇所 |
| --- | --- |
| Docker（ランナーを動かす利用者を `docker` グループに入れる） | 計測用のコンテナのビルドと実行 |
| git / curl / jq | 対象の取得、Node.js の版の解決、送信 |
| 外向きの通信: github.com・Docker Hub・nodejs.org・archive.apache.org・Maven Central・npm レジストリ | ランナーの接続、計測用のコンテナのビルド（ベースイメージ・Node.js・Maven・Trivy・oasdiff・Playwright）、PMD・k6 の取得、対象の依存関係 |
| quality-gate への到達性 | `submit` ジョブ（`QG_BASE_URL`） |
| 十分なディスク（目安 20GB 以上） | 計測用のコンテナのイメージ、Maven / npm のキャッシュ、Trivy の DB |

性能計測（M-03 / M-04）の値が乱れないよう、**専有のマシンに 1 台だけ登録し、他のリポジトリのランナーや常駐サービスを同じマシンに置かないでください**。

### 3.2 登録

1. GitHub の quality-gate リポジトリで **Settings → Actions → Runners → New self-hosted runner** を開き、Linux / x64 を選ぶ
2. 画面に表示されるコマンドでランナーをダウンロード・展開し、登録コマンドを実行する（トークンの有効期限は 1 時間）

   ```bash
   ./config.sh --url https://github.com/<owner>/quality-gate --token <登録トークン>
   ```

   ラベルは既定のまま（`self-hosted`, `Linux`, `X64`）で構いません。ワークフローは `self-hosted` だけで割り当てます
3. サービスとして常駐させる

   ```bash
   sudo ./svc.sh install && sudo ./svc.sh start
   sudo ./svc.sh status   # active (running) なら OK
   ```

   WSL2 で `svc.sh` を使うには systemd の有効化（`/etc/wsl.conf` の `[boot]` に `systemd=true`）が必要です。使わない場合は `./run.sh` を起動したままにします

**Settings → Actions → Runners** でランナーが `Idle` になれば準備完了です。

> セルフホストランナーでは、ワークフローを動かせる人なら誰でもそのマシンで任意のコードを実行できます。収集ランナーは対象のテストコードも実行するため、**quality-gate リポジトリは private のままにしてください**（ワークフローのログと成果物には対象のパスやテスト出力も含まれます）。

### 3.3 対象を読むための GitHub App

対象リポジトリを読むための GitHub App を作り、対象にインストールします。対象が public なら不要です（`QG_COLLECTOR_APP_ID` を設定しなければトークンなしで取得します）。

1. GitHub の **Settings → Developer settings → GitHub Apps → New GitHub App** で App を作り（Webhook は不要）、**Repository permissions** の **Contents** を **Read-only** にする
2. 同じ画面の **Private keys** で秘密鍵を生成し、ダウンロードする
3. **Install App** から対象リポジトリにインストールする（**Only select repositories** で対象だけを選ぶ）

### 3.4 リポジトリの変数とシークレット

**quality-gate リポジトリ**の **Settings → Secrets and variables → Actions** で設定します。対象リポジトリには何も設定しません。

| 種別 | 名前 | 値 |
| --- | --- | --- |
| Variables | `QG_BASE_URL` | 取り込み先の quality-gate の URL（ランナーから届くもの。同じマシンなら `http://localhost:8080`） |
| Variables | `QG_COLLECTOR_APP_ID` | 3.3 の App の App ID |
| Secrets | `QG_COLLECTOR_APP_PRIVATE_KEY` | 3.3 でダウンロードした秘密鍵（PEM の中身全体） |
| Secrets | `QG_INGEST_TOKEN` | アプリの `QG_INGEST_TOKEN` と同じ値（2.3） |

### 3.5 ランナーを止めるとき

ランナーが止まっていると、計測のジョブは待機したまま進みません（24 時間待つと GitHub が取り消します）。
収集ランナーは GitHub ホストランナーへ退避できないため、ランナーを戻したあとで必要な計測を手動実行し直してください。
quality-gate のアプリ（画面）はランナーとは別に動きます。

## 4. 計測対象を設定する

### 4.1 手順

1. `collector/target/profile.env`（計測プロファイル）を書き換える（4.2）
2. 性能を計測するなら `collector/target/k6.js`（負荷試験のシナリオ）も書き換える（4.4）
3. 3.3 の App を対象にインストールする
4. アプリの `QG_REPOSITORY` を同じリポジトリにし、合格ライン（2.4）を決める
5. プルリクエストで main にマージする

計測できるのは Maven（`BACKEND_DIR`）と npm + Vitest（`FRONTEND_DIR`）の構成だけです。使わない側は計測プロファイルで空にしてください。
設定したら、合格ラインを確定する前に一度計測してベースラインを確かめ、対象自身のビルドと結果が一致するかを確かめます（4.5）。

### 4.2 計測プロファイル

計測対象と「どう測るか」を書く `KEY=VALUE` 形式のファイルです（シェルとしては実行せず、値の引用符は 1 組だけ外して読みます）。合格ラインは書きません（2.4）。

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `QG_REPOSITORY` | （必須） | `owner/name`。アプリの `QG_REPOSITORY` と一致させる |
| `DEFAULT_BRANCH` | （必須） | 既定ブランチ。ブランチを指定しない計測はここを測る |
| `DISABLED_METRICS` | 空 | 計測しない指標 ID（空白区切り。例: `M-03 M-04`）。アプリの `QG_DISABLED_METRICS` とそろえる（4.3） |
| `BACKEND_DIR` | 空 | バックエンド（Maven）のディレクトリ。空ならバックエンドを計測しない。末尾のディレクトリ名がコンポーネント名になる |
| `JAVA_VERSION` | `21` | 計測用のコンテナの JDK の版 |
| `TEST_REPORTS` | `surefire-reports/TEST-*.xml failsafe-reports/TEST-*.xml` | M-09 / M-10 で送る JUnit XML（`BACKEND_DIR/target` からの相対、空白区切り） |
| `MUTATION_TARGET_CLASSES` | 空 | M-02 でミューテーションを加えるクラス（PIT の `targetClasses`。空白区切り）。空なら M-02 を計測しない |
| `MUTATION_TARGET_TESTS` | `MUTATION_TARGET_CLASSES` と同じ | 実行するテスト |
| `MUTATION_EXCLUDED_CLASSES` / `MUTATION_EXCLUDED_TESTS` | 空 | 除外するクラス（起動クラスや設定クラス）/ テスト（`*IT` など時間のかかるもの） |
| `MUTATION_THREADS` | `2` | PIT のスレッド数 |
| `OPENAPI_PATH` | 空 | M-07 で比べる OpenAPI 定義（リポジトリ相対）。空なら M-07 を計測しない |
| `FRONTEND_DIR` | 空 | フロントエンド（npm + Vitest）のディレクトリ。空ならフロントエンドを計測しない |
| `NODE_VERSION_FILE` | 空（Node.js 22） | Node.js の版を書いたファイル（`.nvmrc` など。`22` / `v22.21.1` の形に対応） |
| `FRONTEND_COVERAGE_INCLUDE` / `FRONTEND_COVERAGE_EXCLUDE` | 空 | M-01 のカバレッジの分母に含める / 除くファイル（`FRONTEND_DIR` 相対、空白区切り）。含めるファイルを指定しないと、テストが触れたファイルだけの数字になり実態より良く見えます |
| `FRONTEND_COMPLEXITY_SOURCES` / `FRONTEND_COMPLEXITY_EXCLUDE` | `src` / `**/*.spec.ts **/*.test.ts **/*.d.ts` | M-06 で解析するディレクトリ / 除くファイル |
| `A11Y_PAGES` | 空 | M-08 で検査する画面のパス（空白区切り）。アプリの `QG_ACCESSIBILITY_PAGES` と一致させる。空なら M-08 を計測しない |
| `A11Y_READY_SELECTOR` | 空 | 描画が済んだと判断できる要素（CSS セレクタ）。空なら通信が落ち着くまで待つだけ |
| `A11Y_BACKEND_PORT` | 空 | M-08 でバックエンドを起動するポート。対象のフロントエンドの proxy 先に合わせる。空ならバックエンドを起動しない |
| `A11Y_FRONTEND_PORT` / `A11Y_START_TIMEOUT` | `4173` / `120` | `vite preview` のポート / 起動を待つ秒数 |
| `PERF_SCRIPT` | 空 | M-03 / M-04 の k6 シナリオ（`collector/target/` からの相対）。空なら計測しない |
| `PERF_ENVIRONMENT` | `collector` | 計測環境の名前。前回比はこの名前ごとに分かれます。**ランナーのマシンや計測条件を変えたら名前も変える** |
| `PERF_DATASET_PROFILE` | 空 | シードデータの名前（記録用） |
| `PERF_BACKEND_PORT` / `PERF_START_TIMEOUT` | `8080` / `120` | バックエンドを起動するポート / 起動を待つ秒数 |
| `PERF_RUNS` | `3` | 実行回数。3 回未満は計測エラーになる |
| `PERF_JAVA_OPTS` | 空 | バックエンドの JVM の引数（例: `-Xmx512m`） |
| `PERF_WARMUP_SECONDS` / `PERF_DURATION_SECONDS` | `60` / `300` | ウォームアップと計測の秒数。**手元で試すときだけ**短くします（その結果は送らない） |
| `PERF_PROGRESS_INTERVAL` | `30` | 負荷試験の経過をログに出す間隔（秒） |

`PERF_SCRIPT`・`MUTATION_TARGET_CLASSES`・`A11Y_PAGES`・`OPENAPI_PATH` を空にしても計測は止まりますが、アプリで判定する指標のままだと結果が無いため ERROR になります。止めるときは 4.3 のとおり両方で無効にしてください。

### 4.3 計測する指標の切り替え

指標を止めるときは、**計測プロファイルの `DISABLED_METRICS` と、アプリの `QG_DISABLED_METRICS` の両方**に同じ指標 ID を書きます。
前者で計測を止め、後者で判定から外します。片方だけだと、計測しない指標が判定に残って計測エラー（不合格）になるか、判定しない指標を測って時間を使います。

```bash
# 計測プロファイル（collector/target/profile.env）
DISABLED_METRICS=M-03 M-04
# アプリの環境変数
QG_DISABLED_METRICS=M-03,M-04
```

| 指標 | `DISABLED_METRICS` に書くと行わない計測 |
| --- | --- |
| `M-02` | PIT |
| `M-03`（と M-04） | k6 の負荷試験 |
| `M-06` | PMD と ESLint |
| `M-07` | oasdiff |
| `M-08` | 画面のビルドと axe-core |
| `M-05` と `M-11` | Trivy。1 回の走査で両方を出すため、**両方を書いたときだけ**走査しない |
| `M-12` | Trivy のライセンスの走査 |
| `M-01` / `M-09` / `M-10` | ビルドとテストは他の指標の成果物も作るため、**書いても実行する**（判定に使われないだけ） |

### 4.4 負荷試験のシナリオ

`collector/target/k6.js` に k6 のシナリオを書きます（既存のものを写して書き換えます）。スクリプトには `PERF_BASE_URL`・`PERF_SUMMARY`・`PERF_WARMUP_SECONDS`・`PERF_DURATION_SECONDS` が環境変数で渡ります。

- 計測区間のシナリオに `phase: measure`、ウォームアップに `phase: warmup` のタグを付ける
- シナリオ名（`options.scenarios` のキー）をアプリの `QG_PERF_SCENARIOS` と一致させ、シナリオごとに `http_req_duration{scenario:<名前>}` のしきい値を書く（k6 はしきい値のあるタグ付きの指標だけを出力する）
- 計測区間の到達率の合計をアプリの `QG_ARRIVAL_RATE_RPS` と一致させる
- `handleSummary` で `http_reqs{phase:measure}` の rate を「件数 ÷ 計測秒数」に直して `PERF_SUMMARY` に書く。k6 の rate はウォームアップを含む全体の時間で割るため、そのままでは到達率が 5/6 に見え、M-03 が計測エラーになります

k6 とアプリは同じコンテナで動きます。値は前回との比較（劣化の検出）に使ってください。
データベースなど外部のサービスが要る対象は起動できず、外部の有料 API を呼ぶ対象はスタブに差し替える手段ができるまで性能を計測しないでください。

### 4.5 対象自身のビルドと結果が一致するか確かめる

対象を追加したら、同じコミットについて対象自身のビルドの出力と収集ランナーの結果を比べます。

| 指標 | 一致すべきもの |
| --- | --- |
| M-01 | backend / frontend それぞれのブランチカバレッジ |
| M-02 | ミューテーションの総数と検出数。テストが乱数を固定していないと、実行ごとに数件ずれます |
| M-05 | 件数（Trivy の版の違いで差が出うる。差が出たら `versions.env` の版をそろえて確かめる） |
| M-06 | CC 15 超の関数の数（ツールの版や設定の違いで、対象の lint の警告とずれることがあります） |
| M-07 | 破壊的変更の件数（または対象外） |
| M-08 | 検査した画面と重大な違反の件数。対象の e2e が API をモックしていると、実際のバックエンドにつなぐ収集ランナーとは描画が変わりうる |
| M-09 / M-10 | テストの件数（成功・失敗・スキップ） |

like-chatgpt の `b581260` では、JaCoCo・lcov・PMD の各数値とテストの件数が like-chatgpt 自身のビルドと一致しました。
M-02 はミューテーションの総数（143 件）が一致し、検出数は 98〜99 件でした（乱数を使うクラスのテストの結果が実行ごとに変わるため）。

## 5. 計測を実行する

### 5.1 手動実行

計測は手動実行だけです。同じコミットを何度でも計測でき、Run は試行（attempt）として別に残ります。

1. quality-gate の **Actions → collect → Run workflow** を開く
2. 入力して実行する

   | 入力 | 例 | 説明 |
   | --- | --- | --- |
   | branch | （空） | 空なら既定ブランチ |
   | commit | （空）/ `v1.2.0` | 特定のコミット（40 桁）かタグを測るときに指定する。リリース判定にはタグを指定する |

   比較元は自動で決まります（[アーキテクチャ](architecture.md#34-比較元とタグ)）。

3. 実行画面に `collect main` のような名前の実行ができる。`submit` ジョブのログに `Run を作成しました: <runId>`・送信したファイル・判定結果（`判定: PASS` など）とリリース判定の URL が出る。処理失敗なら `submit` ジョブが失敗する
4. quality-gate の画面（リリース判定）で結果を見る

`measure` ジョブの成果物 `collector-reports`（7 日保持）で、送った内容をあとから確認できます。

| 計測 | 所要時間の目安（like-chatgpt） |
| --- | --- |
| 負荷試験が無効 | 数分 + PIT 約 1 分 |
| 負荷試験が有効 | 上に加えて約 18 分（1 回約 6 分 × 3 回） |

初回と、ツールの版を上げた後は、計測用のコンテナのビルドに数分かかります。

### 5.2 リリース判定のために計測する

リリース判定は、指定したコミットの最新の判定済みの Run で結論を出します（指定しなければ最新の計測）。

1. `commit` にリリースのタグ（例: `v1.2.0`）を入れて実行する。branch は空のままでよい（比較元は前のタグになります）
2. 計測が終わったら、quality-gate の画面でそのタグを入れる（判定の履歴からも開けます）。関係者に共有するときは「PDF として保存（印刷）」で証跡を残せます

リリース判定は、計測したときにコミットを指していたタグでコミットを探します。**タグを付ける前に計測したコミットはタグでは見つかりません**。タグを付けてから計測し直すか、コミット SHA で指定してください。
前のタグのコミットも計測しておくと、前回比（破壊的変更・スキップの増加など）が前のリリースとの比較になります。

### 5.3 結果を読むときの注意

- **合否は合格 / 不合格の 2 値**です。合格ライン内の気になる点（前回からの低下、moderate のアクセシビリティ違反など）は判定理由に書き添えます
- **M-03 / M-04 は計測環境（`PERF_ENVIRONMENT`）ごとに比べます**。名前を変えると前回比が出なくなります
- **M-06 は計測したコミットの関数の件数（絶対値）で判定します**。frontend の関数も含みます
- **M-07 の初回**（比較元に OpenAPI 定義が無いとき）は対象外になります
- テストが失敗しても計測は止めません（M-09 に表れます）。ただし PIT はテストがすべて通っていないと動かず、M-02 は ERROR になります
- ビルド自体が失敗すると、その指標の成果物が出ず ERROR になり、Run 全体が FAIL（リリース不可）になります
- 判定し直す機能はありません。直したら計測し直してください

## 6. 手元で試す

ワークフローと同じことを手元で実行できます（Docker と git / curl / jq が必要）。

```bash
WORK=/tmp/qg-collector
# private リポジトリなら GH_TOKEN に読み取り権限のあるトークンを入れる
./collector/bin/fetch.sh "$WORK"
./collector/bin/measure-isolated.sh "$WORK" "$WORK/reports"
QG_BASE_URL=http://localhost:8080 QG_INGEST_TOKEN=<アプリの QG_INGEST_TOKEN> \
  ./collector/bin/submit.sh "$WORK/reports"
```

- `fetch.sh` は環境変数 `QG_BRANCH` / `QG_COMMIT` でワークフローの入力と同じ指定ができます
- `measure.sh` はコンテナの外では動きません。必ず `measure-isolated.sh` を使います
- 負荷試験が有効なら約 18 分かかります。k6 のシナリオを確かめるだけなら、計測プロファイルを写したものに `PERF_RUNS=1`・`PERF_WARMUP_SECONDS=5`・`PERF_DURATION_SECONDS=20` を足して短く実行できます（その結果は quality-gate に送らないでください）

## 7. 計測用のコンテナの設定

`measure` ジョブは計測用のコンテナの中で計測します（[アーキテクチャ](architecture.md#36-計測のコンテナ隔離)）。ランナーの環境変数で次を変えられます。

| 環境変数 | 既定 | 説明 |
| --- | --- | --- |
| `QG_COLLECTOR_MEMORY` | `6g` | コンテナのメモリ上限 |
| `QG_COLLECTOR_BASE_IMAGE` | `eclipse-temurin:<JAVA_VERSION>-jdk-noble` | ベースのイメージ。社内のミラーや社内の CA を入れたイメージを使うときに指定する |
| `QG_COLLECTOR_DOCKER_ARGS` | 空 | `docker run` に足す引数（空白区切り。例: `--env HTTPS_PROXY --env JAVA_TOOL_OPTIONS`、CPU の上限なら `--cpus 4`）。**ソケットやホストのディレクトリを見せる引数は足さない**（隔離の意味が無くなる） |

- イメージのタグは `quality-gate-collector:java<版>-node<版>-...`。古いイメージは `docker image prune` で消せます
- Maven・npm・PMD・k6・Trivy の DB のキャッシュは Docker のボリューム `quality-gate-collector-home` に残ります。消すと（`docker volume rm quality-gate-collector-home`）次の計測で取り直します
- ツールの版は `collector/versions.env` で固定しています。版を上げるときは、前回からの値に段差が出ないか（特に PMD の CC の算出）を確かめてください

## 8. うまくいかないとき

| 症状 | 主な原因と対処 |
| --- | --- |
| 実行してもジョブが始まらない | ランナーが止まっている（3.5） |
| `fetch` が `計測プロファイルがありません` | `collector/target/profile.env` が無い |
| `fetch` が `could not read Username` / `Repository not found` | App が対象にインストールされていない、Contents の権限が無い、または `QG_COLLECTOR_APP_ID` が未設定で private を取得しようとした |
| `measure` で `docker がありません` / `permission denied ... docker.sock` | ランナーに Docker が無い、またはランナーの利用者が `docker` グループに入っていない |
| `measure` の `計測用のコンテナの作成` で失敗する | Docker Hub・nodejs.org・github.com・archive.apache.org に届かない。社内のミラーを使うなら `QG_COLLECTOR_BASE_IMAGE` を指定する |
| `measure` で `Node.js の版を解決できませんでした` | `NODE_VERSION_FILE` の書き方が解釈できない（`22` / `v22.21.1` の形に対応）、または nodejs.org に届かない |
| `measure` で `バックエンドのビルドに失敗しました` | 対象がコンパイルできない、または `JAVA_VERSION` が対象の要求と合っていない |
| `measure` で `M-02: PIT の実行に失敗しました` | テストに失敗がある、テストが JUnit 5 でない、または `MUTATION_TARGET_CLASSES` に合うクラスが無い（`No mutations found`） |
| `measure` で `lcov.info がありません` | `FRONTEND_COVERAGE_INCLUDE` のパターンが一致していない（空白区切りで書く） |
| `measure` で `M-06: ESLint の実行に失敗しました` | `FRONTEND_COMPLEXITY_SOURCES` のディレクトリが無い、または ESLint が設定を読めなかった |
| `measure` で `M-06: 構文を読めず、関数を数えられなかったファイルがあります` | quality-gate 側のパーサが読めない構文のファイルがある。`FRONTEND_COMPLEXITY_EXCLUDE` で外すか、`collector/complexity` のパーサを見直す |
| `measure` で `M-09/M-10: バックエンドのテストの結果がありません` | `TEST_REPORTS` のパターンが一致していない |
| `measure` で `M-08: バックエンドが起動しませんでした` / `フロントエンドが起動しませんでした` | ポートが使われている、起動に外部のサービスが要る、または起動が `A11Y_START_TIMEOUT` 秒に収まらない（ログにアプリの出力の末尾が出る） |
| `measure` で `M-08: 検査ツール（Playwright と Chromium）を用意できませんでした` | 計測用のコンテナのビルドが不完全。イメージを消して作り直す |
| `measure` で `M-03 / M-04: バックエンドが起動しませんでした` | ポート（`PERF_BACKEND_PORT`）が使われている、または起動に外部のサービスが要る |
| `measure` で `M-03 / M-04: k6 を取得できませんでした` | github.com に届かない |
| `submit` が `QG_BASE_URL（Variables）または QG_INGEST_TOKEN（Secrets）が未設定です` | 3.4 の設定漏れ |
| `submit` の Run の作成が 401 | リポジトリの Secret とアプリの `QG_INGEST_TOKEN` が一致していない |
| `submit` の Run の作成が 400（`計測対象のリポジトリは … です`） | 計測プロファイルとアプリの `QG_REPOSITORY` が一致していない |
| 計測していない指標が計測エラーになる | 計測プロファイルの `DISABLED_METRICS` にだけ書き、アプリの `QG_DISABLED_METRICS` に書いていない（4.3） |
| M-03 が ERROR（シナリオがありません） | k6 のシナリオ名とアプリの `QG_PERF_SCENARIOS` が一致していない |
| M-03 が ERROR（成功スループットが到達率の 95% 未満） | アプリが負荷を捌けていない、`handleSummary` で rate を直していない、または到達率の合計と `QG_ARRIVAL_RATE_RPS` が一致していない |
| M-08 が ERROR（検査した画面が足りない） | `A11Y_PAGES` と `QG_ACCESSIBILITY_PAGES` がずれている、画面を読み込めなかった、または `A11Y_READY_SELECTOR` の要素が現れない |
| 画面にログインできない | `QG_LOGIN_USERNAME` / `QG_LOGIN_PASSWORD` が違う。アプリを再起動するとログインし直しになる |
