# 開発環境のセットアップ

前提: JDK 25 / Node.js 24 / Docker。
**WSL2 で作業する場合、リポジトリは Linux ファイルシステム側（`/home/...`）に置いてください。**
`/mnt/c` 配下はファイル I/O が遅く、ビルドと HMR が体感できるほど遅延します。

**アプリを動かすだけなら、手順 1〜3 で足ります。** ブラウザで `http://localhost:8080` を開いてください。
`spring-boot:run` がフロントエンドのビルドと同梱まで自動で行うため（Node.js も Maven が
`backend/target/` に取得します）、`npm run dev` は不要です。

```bash
# 1. データベースを起動する
docker compose up -d db

# 2. バックエンドをビルド・テストする（Testcontainers が PostgreSQL を起動します。動かすだけなら省略可）
cd backend && ./mvnw verify

# 3. アプリを起動する（GitHub App の設定と .env が必要。「ログイン用の GitHub App」を参照）
#    画面も API も http://localhost:8080 で配信されます
./mvnw spring-boot:run
```

**画面（`frontend/`）を開発するときは**、手順 3 の代わりに次の 2 つを別々のターミナルで起動し、
`http://localhost:5173` を開きます。コードを保存するとブラウザに即時反映（HMR）されます。
8080 だけで開発すると、画面を直すたびに `spring-boot:run` を止めて再実行する必要があります。

```bash
# 3'. バックエンドを起動する（フロントエンドのビルドを飛ばして起動を速くする）
cd backend && ./mvnw spring-boot:run -DskipFrontend=true

# 4. 別ターミナルでフロントエンドの dev server を起動する（/api などは 8080 にプロキシされます）
cd frontend && npm ci && npm run dev
```

`-DskipFrontend=true` で起動したバックエンドは画面を同梱しないため、
この場合 `http://localhost:8080` を開いても画面は表示されません（API のみ）。

## ログイン用の GitHub App

ログインは GitHub App の user-to-server 認可フローで行います（[03](../spec/03-design-decisions.md) DD-18）。
ローカルで動かすには、開発者ごとに GitHub App を 1 つ作成し、その認証情報を
バックエンドに渡す必要があります。

1. GitHub の **Settings → Developer settings → GitHub Apps → New GitHub App** で作成する

   | 項目 | 値 |
   | --- | --- |
   | GitHub App name | 任意（GitHub 全体で一意。例: `quality-gate-local-<GitHub ログイン名>`） |
   | Homepage URL | `http://localhost:5173` |
   | Callback URL | `http://localhost:8080/login/oauth2/code/github` と `http://localhost:5173/login/oauth2/code/github` の両方（8080 だけで動かすなら前者のみでよい） |
   | Webhook の Active | チェックを外す |
   | Repository permissions | ログインだけなら設定不要。収集ランナーで private の対象を計測するなら Contents / Pull requests を Read-only にする（[収集ランナーで計測する](../operations/collector.md#1-2-対象を読むための-github-appprivate-リポジトリの場合)） |
   | Where can this GitHub App be installed? | Only on this account |

   Callback URL は、ブラウザで開いたオリジン（8080 で直接開くか、5173 の dev server 経由か）
   によって決まる `redirect_uri` と完全一致している必要があるため、両方を登録します。

2. 作成後の画面で **Client ID** を控え、**Generate a new client secret** でシークレットを発行する
   （シークレットは発行時にしか表示されません）

3. リポジトリ直下の `.env` に書く

   ```bash
   cp .env.example .env
   # .env を編集する
   QG_GITHUB_CLIENT_ID=Iv23li...
   QG_GITHUB_CLIENT_SECRET=...
   ```

   バックエンドは起動時に `.env` を設定ファイルとして読み込みます（`application.yml` の
   `spring.config.import`）。`backend/` から起動しても、リポジトリ直下から起動しても見つかります。
   `.env` は `.gitignore` 済みです。認証情報はコミットしないでください。

   - 値は `KEY=value` の形で書き、引用符で囲まないでください（引用符も値の一部として読まれます）
   - 同じ名前の環境変数が設定されている場合は、環境変数のほうが優先されます
   - Windows 側のエディタで編集した場合は改行コードを LF にしてください
     （CRLF だと値の末尾に `\r` が付きます）

取り込み（Ingest API）を手元で試す場合は、`.env` に `QG_INGEST_TOKEN` も書きます（値は `openssl rand -hex 32` などで作る。
[取り込み](../operations/ingest.md#ローカルで取り込みを試す)）。未設定なら取り込み API はすべて 401 になります。

利用者が 1 件も存在しない初期状態では、**最初にログインしたユーザーが自動的に ADMIN として登録されます**。
2 人目以降は、ADMIN が許可リストに追加するまでログインできません（`/forbidden` に遷移します）。
