# 運用: 環境設定と計測の実行

quality-gate のアプリを動かし、収集ランナーで対象リポジトリを計測するまでの手順です。
仕組みと考え方は [アーキテクチャ](architecture.md#3-測定の仕組み収集ランナー)、指標の定義は [指標](metrics.md) を参照してください。

## 1. 全体の流れ

初めて使うときは次の順に準備します。

| # | やること | 場所 | 節 |
| --- | --- | --- | --- |
| 1 | ログイン用の GitHub App を作る | GitHub | [開発環境](development.md#3-ログイン用の-github-app) |
| 2 | アプリを起動し、`QG_INGEST_TOKEN` を設定する | アプリを動かすマシン | [2](#2-アプリを動かす) |
| 3 | セルフホストランナーを登録する | ランナーのマシン | [3](#3-セルフホストランナーを準備する) |
| 4 | App に対象を読む権限を足し、対象にインストールする | GitHub | [3.3](#33-対象を読むための-github-app) |
| 5 | quality-gate リポジトリに変数とシークレットを設定する | GitHub | [3.4](#34-リポジトリの変数とシークレット) |
| 6 | 計測プロファイルと合格ラインを追加する | quality-gate リポジトリ | [4](#4-計測対象を追加する) |
| 7 | collect ワークフローを手動実行する | GitHub Actions | [5](#5-計測を実行する) |

対象リポジトリには何も置きません。quality-gate での対象の登録操作もありません（初めて計測が届いたときに登録されます）。

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
| `QG_GITHUB_CLIENT_ID` / `QG_GITHUB_CLIENT_SECRET` | ダミー値 | ログイン用 GitHub App の認証情報。未設定でも起動はしますが、ログインできません。**空の値（`QG_GITHUB_CLIENT_ID=`）を書くと起動に失敗します**（`compose.yaml` の `app` では空でもダミー値に置き換えます） |
| `QG_DB_URL` / `QG_DB_USERNAME` / `QG_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/qualitygate` / `qualitygate` / `qualitygate` | 接続先 DB。`compose.yaml` の `db` と一致しています |
| `QG_ARTIFACT_ROOT` | `./data/artifacts` | 成果物の保存先（相対パスは起動したディレクトリから） |
| `QG_INGEST_TOKEN` | なし | Ingest Token（2.3）。未設定なら取り込み API はすべて 401 |
| `QG_BASE_URL` | `http://localhost:8080` | 取り込み API の応答に載せる Run 詳細の URL の組み立てに使う |
| `QG_RETENTION_RUN_DAYS` / `QG_RETENTION_ARTIFACT_DAYS` / `QG_RETENTION_AUDIT_LOG_DAYS` | `730` / `90` / `730` | データの保持期間（日）。未設定または 0 なら既定値。下限（30 / 1 / 365 日）を下回ると起動しません（[保持期間](features/retention/design.md)） |
| `QG_SCHEDULE_ZONE` | `Asia/Tokyo` | 日次バッチ（03:00 保持期間の削除、03:10 滞留した Run の後始末）と、リリース判定の CSV の出力日時のタイムゾーン |
| `QG_LOG_FORMAT` | なし（テキスト） | `ecs` / `logstash` / `gelf` で 1 行 1 JSON のログにします（`requestId` / `runId` が項目として載る）。`compose.yaml` の `app` では `ecs` |

- 使わない任意の項目は `.env` に書かないでください。空の値を書くと既定値より優先され、起動に失敗するものがあります（`.env.example` ではコメントアウトしています）
- `docker compose --profile full` の `app` コンテナに渡るのは `compose.yaml` に列挙した変数だけです（`QG_BASE_URL` / `QG_SCHEDULE_ZONE` を使うなら `compose.yaml` に足してください）。DB の接続先と `QG_ARTIFACT_ROOT` はコンテナ用の値で上書きされます
- 日次バッチの時刻は `application.yml` の `quality-gate.schedule.*`（cron。`-` で無効）で変えられます

### 2.3 Ingest Token

収集ランナーが計測結果を送るための鍵で、すべての対象で共通の 1 つです。アプリの `QG_INGEST_TOKEN` と、quality-gate リポジトリの Secret `QG_INGEST_TOKEN`（3.4）に同じ値を入れます。

```bash
openssl rand -hex 32
```

交換するときは、送信を止めないよう次の順で行います（漏えいしたときも同じ手順です）。

1. アプリの `QG_INGEST_TOKEN` を `<古い値>,<新しい値>` にして再起動する（両方を受け付ける）
2. quality-gate リポジトリの Secret `QG_INGEST_TOKEN` を新しい値にする
3. アプリの `QG_INGEST_TOKEN` を新しい値だけにして再起動する

### 2.4 最初のログイン

利用者が 1 人もいない状態では、**最初にログインした GitHub ユーザーが自動的に Admin として登録されます**。
2 人目以降は、Admin が管理画面（`/admin/users`）で許可リストに追加するまでログインできません。

## 3. セルフホストランナーを準備する

収集ランナー（`collect.yml` の `fetch` / `measure` / `submit` のすべて）はセルフホストランナー（`runs-on: self-hosted`）で動きます。
PR の CI（`ci.yml`）は GitHub ホストランナーで動くため、ランナーが止まっていても PR の CI は止まりません。

### 3.1 マシン

Linux x64 を想定します（WSL2 でも可）。JDK・Node.js・Chromium などは計測用のコンテナに入るため、マシンに入れる必要はありません。

| 必要なもの | 使う箇所 |
| --- | --- |
| Docker（ランナーを動かす利用者を `docker` グループに入れる） | 計測用のコンテナのビルドと実行 |
| git / curl / jq | 対象の取得、Node.js の版の解決、送信 |
| 外向きの通信: github.com・Docker Hub・nodejs.org・archive.apache.org・Maven Central・npm レジストリ | ランナーの接続、計測用のコンテナのビルド（ベースイメージ・Node.js・Maven・Trivy・oasdiff・yq・Playwright）、PMD・k6 の取得、対象の依存関係 |
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

ログイン用の GitHub App に権限を足し、対象リポジトリにインストールします。対象が public なら不要です（`QG_COLLECTOR_APP_ID` を設定しなければトークンなしで取得します）。

1. GitHub の **Settings → Developer settings → GitHub Apps →（quality-gate の App）→ Permissions & events** で、**Repository permissions** の **Contents** と **Pull requests** を **Read-only** にする
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

## 4. 計測対象を追加する

### 4.1 手順

1. `collector/targets/<owner>__<name>.env`（計測プロファイル）を作る。既存のものを写して書き換えるのが早いです（4.2）
2. `collector/targets/<owner>__<name>.gate.yml`（合格ライン）を作る。無いと計測の前に止まります（4.4）
3. 性能を計測するなら `collector/targets/<owner>__<name>.k6.js`（負荷試験のシナリオ）も作る（4.5）
4. 3.3 の App を対象にもインストールする
5. プルリクエストで main にマージする

計測できるのは Maven（`BACKEND_DIR`）と npm + Vitest（`FRONTEND_DIR`）の構成だけです。使わない側は計測プロファイルで空にしてください。
追加したら、合格ラインを確定する前に一度計測してベースラインを確かめ、対象自身のビルドと結果が一致するかを確かめます（4.6）。

### 4.2 計測プロファイル

「どう測るか」だけを書く `KEY=VALUE` 形式のファイルです（シェルとしては実行せず、値の引用符は 1 組だけ外して読みます）。
何を測るか（指標のオン・オフ）は書きません（4.3）。

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `QG_REPOSITORY` | （必須） | `owner/name`。ファイル名と一致させる |
| `DEFAULT_BRANCH` | （必須） | 既定ブランチ。計測のたびに quality-gate に送られ、トレンドの既定の系列になる |
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
| `A11Y_PAGES` | 空 | M-08 で検査する画面のパス（空白区切り）。合格ラインの `accessibility.pages` と一致させる。空なら M-08 を計測しない |
| `A11Y_READY_SELECTOR` | 空 | 描画が済んだと判断できる要素（CSS セレクタ）。空なら通信が落ち着くまで待つだけ |
| `A11Y_BACKEND_PORT` | 空 | M-08 でバックエンドを起動するポート。対象のフロントエンドの proxy 先に合わせる。空ならバックエンドを起動しない |
| `A11Y_FRONTEND_PORT` / `A11Y_START_TIMEOUT` | `4173` / `120` | `vite preview` のポート / 起動を待つ秒数 |
| `PERF_SCRIPT` | 空 | M-03 / M-04 の k6 シナリオ（`collector/targets/` からの相対）。空なら計測しない |
| `PERF_ENVIRONMENT` | `collector` | 計測環境の名前。前回比とトレンドはこの名前ごとに分かれます。**ランナーのマシンや計測条件を変えたら名前も変える** |
| `PERF_DATASET_PROFILE` | 空 | シードデータの名前（記録用） |
| `PERF_BACKEND_PORT` / `PERF_START_TIMEOUT` | `8080` / `120` | バックエンドを起動するポート / 起動を待つ秒数 |
| `PERF_RUNS` | `3` | 実行回数。3 回未満は WARN になる |
| `PERF_JAVA_OPTS` | 空 | バックエンドの JVM の引数（例: `-Xmx512m`） |
| `PERF_WARMUP_SECONDS` / `PERF_DURATION_SECONDS` | `60` / `300` | ウォームアップと計測の秒数。**手元で試すときだけ**短くします（その結果は送らない） |
| `PERF_PROGRESS_INTERVAL` | `30` | 負荷試験の経過をログに出す間隔（秒） |
| `SKIP_METRICS` | 空 | 常にスキップを申告する指標 ID（空白区切り）。合格ラインの `skippable_metrics` に含めた指標だけ申告できます |

`PERF_SCRIPT`・`MUTATION_TARGET_CLASSES`・`A11Y_PAGES`・`OPENAPI_PATH` を空にしても計測は止まりますが、合格ラインで有効のままだと結果が無いため ERROR になります。止めるときは合格ラインの `enabled` を使ってください。

### 4.3 計測する指標の切り替え

指標のオン・オフは、合格ライン（`*.gate.yml`）の `metrics.<指標>.enabled` **1 か所だけ**で切り替えます。
収集ランナーは計測の前に合格ラインを読み、`enabled: false` の指標は計測自体を行いません（判定に使われないため、スキップの申告もしません）。
計測プロファイルの設定は残しておけます。戻すときは `enabled: true` にするだけです。

```yaml
metrics:
  performance:
    enabled: false   # 負荷試験（M-03 / M-04）を止める
```

| 合格ラインの指標 | `enabled: false` で行わない計測 |
| --- | --- |
| `mutation_score` | M-02（PIT） |
| `performance` | M-03 / M-04（k6） |
| `cyclomatic_complexity` | M-06（PMD と ESLint） |
| `api_contract` | M-07（oasdiff） |
| `accessibility` | M-08（画面のビルドと axe-core） |
| `vulnerabilities` と `secrets` | M-05 / M-11（Trivy）。1 回の走査で両方を出すため、**両方を無効にしたときだけ**走査しない |
| `licenses` | M-12（Trivy のライセンスの走査） |
| `branch_coverage` / `test_results` | ビルドとテストは他の指標の成果物も作るため、**無効にしても実行する**（判定に使われないだけ） |

YAML 1.1 として読むため、引用符の無い `no` / `off` も false になります（`"false"` のような文字列は有効のまま）。

### 4.4 合格ライン

書式と検証の規則は [合格ライン](features/gate-config/design.md)、各しきい値の意味は [指標](metrics.md) にあります。

- 変更はプルリクエストで行い、main にマージした後の計測から使われます。各 Run には合格ラインを送った quality-gate のコミットが記録されます
- PR を計測するなら、`execution.skippable_metrics` に `mutation_score` と `performance` が必要です（既定値にも入っています）。無いと、PR の Run の M-02 / M-03 / M-04 が ERROR になります
- M-08 を計測するなら、`accessibility.pages` を計測プロファイルの `A11Y_PAGES` と一致させます
- 性能を計測するなら、`performance.scenarios` と `arrival_rate_rps` を k6 のシナリオと一致させます
- 既存のコードで許容するもの（複雑な関数、誤検出など）は `exclusions` で外し、理由をコミットに残します

### 4.5 負荷試験のシナリオ

`collector/targets/<owner>__<name>.k6.js` に k6 のシナリオを書きます（既存のものを写して書き換えます）。スクリプトには `PERF_BASE_URL`・`PERF_SUMMARY`・`PERF_WARMUP_SECONDS`・`PERF_DURATION_SECONDS` が環境変数で渡ります。

- 計測区間のシナリオに `phase: measure`、ウォームアップに `phase: warmup` のタグを付ける
- シナリオ名（`options.scenarios` のキー）を合格ラインの `performance.scenarios` と一致させ、シナリオごとに `http_req_duration{scenario:<名前>}` のしきい値を書く（k6 はしきい値のあるタグ付きの指標だけを出力する）
- 計測区間の到達率の合計を合格ラインの `performance.arrival_rate_rps` と一致させる
- `handleSummary` で `http_reqs{phase:measure}` の rate を「件数 ÷ 計測秒数」に直して `PERF_SUMMARY` に書く。k6 の rate はウォームアップを含む全体の時間で割るため、そのままでは到達率が 5/6 に見え、M-03 が WARN になります

k6 とアプリは同じコンテナで動きます。値は前回との比較（劣化の検出）に使ってください。
データベースなど外部のサービスが要る対象は起動できず、外部の有料 API を呼ぶ対象はスタブに差し替える手段ができるまで性能を計測しないでください。

### 4.6 対象自身のビルドと結果が一致するか確かめる

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
   | repository | `ymiyamoto63/like-chatgpt` | 計測プロファイルがあるリポジトリ |
   | branch | （空） | 空なら既定ブランチ。PR の場合は PR のブランチ名 |
   | commit | （空）/ `v1.2.0` | 特定のコミット（40 桁）かタグを測るときに指定する |
   | pull_request | （空） | PR を計測するときの番号 |

   比較元は自動で決まります（[アーキテクチャ](architecture.md#34-比較元とタグ)）。

3. 実行画面に `ymiyamoto63/like-chatgpt main` のような名前の実行ができる。`submit` ジョブのログに `Run を作成しました: <runId>`・送信したファイル・判定結果（`判定: PASS` など）が出る。処理失敗（合格ラインの誤りなど）なら `submit` ジョブが失敗する
4. quality-gate の Run 詳細で結果を見る（`triggeredBy` は `collector`）

`measure` ジョブの成果物 `collector-reports`（7 日保持）で、送った内容をあとから確認できます。

| 計測 | 所要時間の目安（like-chatgpt） |
| --- | --- |
| PR（M-02・M-03 / M-04 をスキップ） | 数分 |
| ブランチ・コミット・タグ（負荷試験が無効） | 数分 + PIT 約 1 分 |
| ブランチ・コミット・タグ（負荷試験が有効） | 上に加えて約 18 分（1 回約 6 分 × 3 回） |

初回と、ツールの版を上げた後は、計測用のコンテナのビルドに数分かかります。

### 5.2 リリース判定のために計測する

リリース判定（S-08）は、指定したコミットの**完全計測**の Run で結論を出します。PR の計測（部分計測）では「判定できない」になります。

1. `commit` にリリースのタグ（例: `v1.2.0`）を入れて実行する。branch と base は空のままでよい（比較元は前のタグになります）
2. 計測が終わったら、quality-gate のリポジトリ詳細 → リリース判定で同じタグを入れる

リリース判定は、計測したときにコミットを指していたタグでコミットを探します。**タグを付ける前に計測したコミットはタグでは見つかりません**。タグを付けてから計測し直すか、コミット SHA で指定してください。
前のタグのコミットも計測しておくと、前回比と違反の新規 / 継続 / 解消が前のリリースとの比較になります。

### 5.3 結果を読むときの注意

- **M-02 と M-03 / M-04 は PR 以外の Run にだけ値が付きます**。PR の Run では SKIP で部分計測になります。PR で下がるかどうかは、マージ後のブランチの Run で分かります
- **M-03 / M-04 は計測環境（`PERF_ENVIRONMENT`）ごとに比べます**。名前を変えると前回比が出なくなり、トレンドも新しい系列になります
- **M-06 は計測したコミットの関数の件数（絶対値）で判定します**。frontend の関数も含みます
- **M-11 / M-12 は合格ラインで有効にしたときだけ判定します**（既定は無効）
- **M-07 の初回**（比較元に OpenAPI 定義が無いとき）は対象外になります
- テストが失敗しても計測は止めません（M-09 に表れます）。ただし PIT はテストがすべて通っていないと動かず、M-02 は ERROR になります
- ビルド自体が失敗すると、その指標の成果物が出ず ERROR になり、Run 全体が FAIL になります
- 再評価はその Run とともに送られた合格ラインで判定し直します

## 6. 手元で試す

ワークフローと同じことを手元で実行できます（Docker と git / curl / jq が必要）。

```bash
WORK=/tmp/qg-collector
# private リポジトリなら GH_TOKEN に読み取り権限のあるトークンを入れる
./collector/bin/fetch.sh ymiyamoto63/like-chatgpt "$WORK"
./collector/bin/measure-isolated.sh ymiyamoto63/like-chatgpt "$WORK" "$WORK/reports"
QG_BASE_URL=http://localhost:8080 QG_INGEST_TOKEN=<アプリの QG_INGEST_TOKEN> \
  ./collector/bin/submit.sh "$WORK/reports"
```

- `fetch.sh` は環境変数 `QG_BRANCH` / `QG_COMMIT` / `QG_PR_NUMBER` でワークフローの入力と同じ指定ができます
- `measure.sh` はコンテナの外では動きません。必ず `measure-isolated.sh` を使います
- PR 以外の計測では負荷試験（約 18 分）も動きます。k6 のシナリオを確かめるだけなら、計測プロファイルを写したものに `PERF_RUNS=1`・`PERF_WARMUP_SECONDS=5`・`PERF_DURATION_SECONDS=20` を足して短く実行できます（その結果は quality-gate に送らないでください）

## 7. 計測用のコンテナの設定

`measure` ジョブは計測用のコンテナの中で計測します（[アーキテクチャ](architecture.md#36-計測のコンテナ隔離)）。ランナーの環境変数で次を変えられます。

| 環境変数 | 既定 | 説明 |
| --- | --- | --- |
| `QG_COLLECTOR_MEMORY` | `6g` | コンテナのメモリ上限 |
| `QG_COLLECTOR_BASE_IMAGE` | `eclipse-temurin:<JAVA_VERSION>-jdk-noble` | ベースのイメージ。社内のミラーや社内の CA を入れたイメージを使うときに指定する |
| `QG_COLLECTOR_DOCKER_ARGS` | 空 | `docker run` に足す引数（空白区切り。例: `--env HTTPS_PROXY --env JAVA_TOOL_OPTIONS`、CPU の上限なら `--cpus 4`）。**ソケットやホストのディレクトリを見せる引数は足さない**（隔離の意味が無くなる） |

- イメージのタグは `quality-gate-collector:java<版>-node<版>-...`。古いイメージは `docker image prune` で消せます
- Maven・npm・PMD・k6・Trivy の DB のキャッシュは Docker のボリューム `quality-gate-collector-home` に残り、対象の間で共有されます。消すと（`docker volume rm quality-gate-collector-home`）次の計測で取り直します
- ツールの版は `collector/versions.env` で固定しています。版を上げるときは、トレンドに段差が出ないか（特に PMD の CC の算出）を確かめてください

## 8. うまくいかないとき

| 症状 | 主な原因と対処 |
| --- | --- |
| 実行してもジョブが始まらない | ランナーが止まっている（3.5） |
| `fetch` が `計測プロファイルがありません` | `collector/targets/` にそのリポジトリの `.env` が無い、または repository の綴りが違う |
| `fetch` が `could not read Username` / `Repository not found` | App が対象にインストールされていない、Contents の権限が無い、または `QG_COLLECTOR_APP_ID` が未設定で private を取得しようとした |
| `measure` で `docker がありません` / `permission denied ... docker.sock` | ランナーに Docker が無い、またはランナーの利用者が `docker` グループに入っていない |
| `measure` の `計測用のコンテナの作成` で失敗する | Docker Hub・nodejs.org・github.com・archive.apache.org に届かない。社内のミラーを使うなら `QG_COLLECTOR_BASE_IMAGE` を指定する |
| `measure` で `Node.js の版を解決できませんでした` | `NODE_VERSION_FILE` の書き方が解釈できない（`22` / `v22.21.1` の形に対応）、または nodejs.org に届かない |
| `measure` / `submit` で `合格ラインがありません` | `collector/targets/<owner>__<name>.gate.yml` が無い |
| `measure` で `合格ラインを読めませんでした` | 合格ラインが YAML として読めない（yq のエラーがログに出る） |
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
| `submit` が `判定に失敗しました（CONFIG_VALIDATION_FAILED）` | 合格ラインの誤り。Run 詳細と設定の画面に行番号つきの理由が出る |
| PR の Run の M-02 / M-03 / M-04 が ERROR（スキップが許容されていない） | 合格ラインの `execution.skippable_metrics` に `mutation_score` と `performance` が必要 |
| M-03 が ERROR（シナリオがありません） | k6 のシナリオ名と合格ラインの `performance.scenarios` が一致していない |
| M-03 が WARN（成功スループットが到達率の 95% 未満） | アプリが負荷を捌けていない、`handleSummary` で rate を直していない、または到達率の合計と `arrival_rate_rps` が一致していない |
| M-08 が ERROR（検査した画面が足りない） | `A11Y_PAGES` と `accessibility.pages` がずれている、画面を読み込めなかった、または `A11Y_READY_SELECTOR` の要素が現れない |
