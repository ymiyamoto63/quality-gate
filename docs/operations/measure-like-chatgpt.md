# like-chatgpt を計測する手順

[ymiyamoto63/like-chatgpt](https://github.com/ymiyamoto63/like-chatgpt) を計測対象として登録し、
全指標（性能を除く）の成果物を quality-gate に取り込んで判定が出るところまでの手順です。

一般的な前提と最小構成は [対象リポジトリの前提と最小構成](target-repository.md)、
取り込みの仕組みは [CI からの取り込み](ingest.md) を参照してください。

## like-chatgpt 側の準備状況

like-chatgpt には計測に必要なものがすでに揃っているため、**like-chatgpt 側の変更は不要**です。

| ファイル | 役割 |
| --- | --- |
| `.quality-gate.yml` | 合格ラインと有効な指標。`enforcement: report-only`（CI を止めない） |
| `.github/workflows/quality-gate.yml` | `measure` ジョブで計測して `reports/` にまとめ、`submit` ジョブで送信する |
| `scripts/quality-gate-submit.sh` | Run 作成 → 成果物のアップロード（存在するものだけ） → finalize を行う送信スクリプト |
| `docs/quality-gate.md` | like-chatgpt 側から見た計測の説明 |

計測する指標と、送信スクリプトが読む成果物の場所は次のとおりです。

| 指標 | ツール | 送信スクリプトが読むファイル | `type` / `component` |
| --- | --- | --- | --- |
| M-01 ブランチカバレッジ | JaCoCo | `reports/backend/jacoco.xml` | `jacoco-xml` / `backend` |
| M-01 ブランチカバレッジ | Vitest（v8） | `reports/frontend-coverage/lcov.info` | `lcov` / `frontend` |
| M-02 ミューテーションスコア | PIT | `reports/backend/mutations.xml` | `pit-xml` / `backend`（`mutationScope=all`） |
| M-03〜05 性能 | — | — | `.quality-gate.yml` で無効 |
| M-06 脆弱性 | Trivy | `reports/trivy.sarif` | `sarif` |
| M-07 循環的複雑度 | PMD | `reports/backend/pmd.xml` | `pmd-xml` / `backend` |
| M-08 API 契約テスト | JUnit（`*ControllerTest` のみ） | `reports/contract/TEST-*.xml` | `junit-xml` / `backend` |
| M-09 API 破壊的変更 | oasdiff | `reports/oasdiff.json` | `oasdiff-json` / `backend` |
| M-10 アクセシビリティ | Playwright + axe-core | `reports/axe-results.json` | `axe-json` / `frontend` |

計測と送信のやり方は 2 通りあります。まず [A. ローカルで計測して送る](#a-ローカルで計測して送る) で判定が出ることを確かめ、
その後 [B. GitHub Actions から送る](#b-github-actions-から送る) に移るのがおすすめです。

## 1. quality-gate にリポジトリを登録する

1. quality-gate を起動し、ADMIN でログインする（[開発環境のセットアップ](../development/setup.md)）
2. **管理 › リポジトリ管理（S-08）** で owner に `ymiyamoto63`、name に `like-chatgpt` を入力して登録する
3. 同じ画面で Ingest Token を発行し、控えておく（**発行時に一度だけ**表示される）

コンポーネント（`backend` / `frontend`）は S-08 で事前に定義しなくても取り込めます。

## A. ローカルで計測して送る

### 必要なもの

| 必要なもの | 使う箇所 |
| --- | --- |
| JDK 21 | バックエンドのビルド・テスト・PIT（Maven は同梱の `mvnw` を使う） |
| Node.js 22（`.nvmrc`） | フロントエンドのテスト・Playwright |
| Docker | oasdiff（M-09）と Trivy（M-06）をコンテナで実行する |
| git / curl / jq | 比較元コミットの解決と送信スクリプト |

### A-1. like-chatgpt を取得する

M-09 で比較元のコミットを参照するため、**shallow clone にしないでください**。

```bash
git clone https://github.com/ymiyamoto63/like-chatgpt.git
cd like-chatgpt
```

以降のコマンドはすべて like-chatgpt のルートで実行します。

### A-2. 計測する

ワークフローの `measure` ジョブと同じことを順に行い、成果物を `reports/` に揃えます。

```bash
rm -rf reports && mkdir -p reports/backend reports/contract

# M-01（JaCoCo）/ M-07（PMD）/ M-08（JUnit XML）と api/openapi.yml の生成
./backend/mvnw -f backend/pom.xml -B verify
git diff --exit-code api/openapi.yml   # 差分が出たら api/openapi.yml が古い（コミット漏れ）

# M-02（PIT）。数分かかる。省略する場合は A-3 で M-02 のスキップを申告する
./backend/mvnw -f backend/pom.xml -B -P mutation test

# バックエンドの成果物を reports/ に集める
cp backend/target/site/jacoco/jacoco.xml reports/backend/
cp backend/target/pmd.xml reports/backend/
cp backend/target/pit-reports/mutations.xml reports/backend/ || true
# M-08 は API を MockMvc で検証するテストだけ。単体テストまで送ると M-08 が全テストの成功率になる
cp backend/target/surefire-reports/TEST-*ControllerTest.xml reports/contract/
```

M-09 は比較元の `api/openapi.yml` との差分を oasdiff で取ります。
比較元は、main で計測するなら直前のコミット、作業ブランチで計測するなら main との merge-base です。

```bash
# main で計測する場合
BASE=$(git rev-parse HEAD~1)
# 作業ブランチで計測する場合は代わりにこちら
# BASE=$(git merge-base HEAD origin/main)

if git show "$BASE:api/openapi.yml" > reports/openapi-base.yml 2>/dev/null; then
  docker run --rm -v "$PWD:/w" -w /w tufin/oasdiff \
    breaking reports/openapi-base.yml api/openapi.yml --format json > reports/oasdiff.json
  [ -s reports/oasdiff.json ] || echo '[]' > reports/oasdiff.json
else
  echo '[]' > reports/oasdiff.json
  touch reports/oasdiff-base-spec-missing   # 比較元に定義が無い（新規 API）として送る
fi
```

フロントエンドと脆弱性スキャンです。

```bash
# M-01（Vitest のカバレッジ → reports/frontend-coverage/lcov.info）
(cd frontend && npm ci && npm run test:coverage)

# M-06（Trivy → reports/trivy.sarif）。CI の trivy-action と同じく fs スキャン
docker run --rm -v "$PWD:/w" -w /w -v trivy-cache:/root/.cache/ aquasec/trivy \
  fs --format sarif --output reports/trivy.sarif --severity CRITICAL,HIGH,MEDIUM .

# M-10（Playwright + axe-core → reports/axe-results.json）。違反があるとテストは失敗するが、成果物は出る
(cd frontend && npx playwright install chromium && npm run test:a11y) || true
```

- `test:a11y` は like-chatgpt の dev server を **ポート 5173** で起動し、API はテスト内でモックします（バックエンドの起動は不要）。
  5173 で別のサーバーが動いていると、Playwright はそれを**そのまま再利用**して検査します。
  quality-gate のフロントエンドを `npm run dev`（同じく 5173）で動かしている場合は、先に止めてください。
  quality-gate を `spring-boot:run` や Docker で起動している場合（8080 のみ）は影響しません
- Playwright のブラウザが OS のライブラリ不足で起動しないときは、一度だけ `npx playwright install-deps chromium`（sudo が必要）を実行します。
  同梱ブラウザを取得できない環境では `E2E_CHROMIUM` に Chromium の実行ファイルを指定します

揃ったかを確認します。`mutations.xml` 以外が 1 つでも欠けていると、その指標は ERROR になり Run 全体が FAIL になります。

```bash
ls -l reports/backend/{jacoco,pmd,mutations}.xml reports/contract/ \
      reports/frontend-coverage/lcov.info reports/trivy.sarif \
      reports/oasdiff.json reports/axe-results.json
```

### A-3. 送信する

```bash
export QG_BASE_URL=http://localhost:8080          # quality-gate の URL
export QG_INGEST_TOKEN=qg_xxxxxxxx_xxxxxxxx        # 手順 1 で発行した Ingest Token
export QG_REPOSITORY=ymiyamoto63/like-chatgpt
export QG_COMMIT_SHA=$(git rev-parse HEAD)
export QG_BRANCH=$(git branch --show-current)
export QG_RUNNER_TYPE=self-hosted
export QG_TRIGGERED_BY=local
# PIT を省略した場合だけ（.quality-gate.yml でスキップを許可しているのは M-02 のみ）
# export QG_SKIPPED="M-02"

./scripts/quality-gate-submit.sh
```

出力の最初の行に `Run を作成しました: <runId>` が出ます。続けて送信したファイルが 1 行ずつ表示され、
最後に finalize の応答（`202`）が JSON で出れば送信は完了です。
`::warning::成果物がありません` が出た指標は送られていないので、A-2 の該当手順を見直してください。

### A-4. 結果を確認する

```bash
RUN_ID=<Run を作成しました の後ろの値>
curl -s "$QG_BASE_URL/api/v1/runs/$RUN_ID/status" -H "Authorization: Bearer $QG_INGEST_TOKEN" | jq
```

判定は数秒で終わります。`status` が `EVALUATED` になり、`verdict` が `PASS` / `PASS_WITH_WARNINGS` / `FAIL` のいずれかになれば成功です。
画面ではダッシュボードに like-chatgpt が現れ、Run 詳細で指標ごとの実測値と合格ライン、違反一覧で個々の違反を確認できます。

## B. GitHub Actions から送る

like-chatgpt のワークフローは、送信先が設定されていれば `push`（main）と `pull_request` のたびに計測・送信します。
未設定の間は計測と成果物（`quality-gate-reports`）の保存だけを行い、送信はスキップします。

### B-1. 送信先から quality-gate に到達できるようにする

`measure` / `submit` の両ジョブは **GitHub ホストランナー（`ubuntu-latest`）** で動きます。
そのため `QG_BASE_URL` には**インターネットから到達できる** quality-gate の URL が必要です。
手元の `http://localhost:8080` は指定できません。

quality-gate を外部に公開できない場合は、次のどちらかにします。

- A の手順でローカルから送る（CI では計測と成果物の保存だけを行う）
- like-chatgpt の `submit` ジョブの `runs-on` を、quality-gate に到達できるネットワークのセルフホストランナーに変える
  （ランナーの準備は [セルフホストランナー](self-hosted-runner.md)。like-chatgpt のワークフローの変更が必要）

### B-2. 変数とシークレットを設定する

like-chatgpt の **Settings → Secrets and variables → Actions** で次を設定します。

| 種別 | 名前 | 値 |
| --- | --- | --- |
| Variables | `QG_BASE_URL` | B-1 で用意した quality-gate の URL |
| Secrets | `QG_INGEST_TOKEN` | 手順 1 で発行した Ingest Token |

### B-3. 実行して確認する

1. like-chatgpt の **Actions → quality-gate → Run workflow** で main を指定して実行する
2. `submit` ジョブの「quality-gate へ送信」ステップのログに `Run を作成しました: <runId>` と送信したファイルの一覧が出ていることを確かめる
3. quality-gate のダッシュボードで like-chatgpt の Run を開く

CI から送る値はワークフローが自動で埋めます（PR ではマージコミットではなく PR の先頭コミット、`runnerType` は `github-hosted`）。
PIT の成果物が無かったときは、M-02 のスキップを自動で申告します。
送信ステップは `continue-on-error: true` のため、quality-gate 側が落ちていても like-chatgpt の CI は止まりません。
送信の失敗はステップのログで確認してください。

## 判定結果を読むときの注意

- **`enforcement: report-only`** のため、判定が FAIL でも like-chatgpt の CI は止まりません
- **M-07 は FAIL になりません。** 送信スクリプトは PMD の結果を head の分しか送らない（`scope=base` の解析結果を送らない）ため、
  quality-gate は「新規・悪化した関数」を判定できません。CC 15 超の関数があっても
  「ベース比較ができないため、既存の複雑度超過 N 件は新規として計上していません」と表示され、
  CC 11〜15 の関数があるときだけ WARN になります（[指標・判定仕様](../initial/02-metrics-spec.md)「ベース側の CC 取得」）
- **M-08 は `*ControllerTest` の成功率**です。like-chatgpt には Pact などの契約テストが無いため、MockMvc で API を検証するテストを契約テストとして扱っています
- **M-09 の初回**（比較元に `api/openapi.yml` が無いとき）は「対象外」になります
- **M-10 の検査対象は `/` の 1 画面**です（`.quality-gate.yml` の `accessibility.pages`）。重大 0 件は適合の十分条件ではありません
- **M-03〜05（性能）** は k6 のシナリオと専有の計測環境が無いため無効です

## うまくいかないとき

| 症状 | 主な原因と対処 |
| --- | --- |
| Run 作成が `404` | quality-gate に `ymiyamoto63/like-chatgpt` が登録されていない、または綴りが違う |
| Run 作成が `REPOSITORY_MISMATCH` | 別のリポジトリ（quality-gate 自身など）用の Ingest Token を使っている |
| Run 作成が `401` | Ingest Token の値が違う、または失効している。S-08 で再発行する |
| M-02 以外の指標が「成果物が提出されていません」で FAIL | その成果物が `reports/` に無かった。送信時の `::warning::成果物がありません` を確認する |
| M-02 のスキップ申告が ERROR | `QG_SKIPPED` に M-02 以外を書いた。スキップを許可しているのは `mutation_score` だけ |
| M-10 が ERROR（検査したページが無い、`pages` の画面が検査されていない、など） | `test:a11y` が途中で落ちて `reports/axe-results.json` が空、または Playwright が別のサーバー（5173 番の quality-gate など）を検査した |
| M-09 の oasdiff が失敗する | shallow clone で比較元のコミットが無い。`git fetch --unshallow` するか clone し直す |
| CI の送信ステップが `Could not resolve host` / 接続タイムアウト | `QG_BASE_URL` に GitHub ホストランナーから到達できない（B-1） |
| CI で「送信をスキップします」の notice が出る | `QG_BASE_URL`（Variables）か `QG_INGEST_TOKEN`（Secrets）が未設定。種別の取り違え（Secrets に URL を入れた等）にも注意 |
