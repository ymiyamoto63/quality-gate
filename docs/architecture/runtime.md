# 起動の仕組み

## 全体像

```
                    ┌──────────────────────── ブラウザ ────────────────────────┐
                    │  開発時: http://localhost:5173   /  同梱時: :8080       │
                    └───────────────┬──────────────────────────┬──────────────┘
                                    │ 画面（SPA）               │ /api, /oauth2, /login/oauth2, ...
                                    ▼                          ▼
             ┌─────────────────────────────┐   proxy   ┌───────────────────────────────┐
  開発時のみ │ Vite dev server (:5173)     │ ────────▶ │ Spring Boot (:8080)           │
             │  frontend/src を HMR で配信  │           │  ├ REST API (/api/v1/**)      │
             └─────────────────────────────┘           │  ├ OAuth ログイン（GitHub App）│
                                                       │  ├ 同梱 SPA (classpath:/static)│
   CI（GitHub Actions）── Ingest API ────────────────▶ │  └ JobWorker（判定ジョブ）     │
    Bearer qg_xxx_yyy    POST /api/v1/runs/...         └──────┬──────────────┬─────────┘
                                                              │ JDBC         │ ファイル
                                                              ▼              ▼
                                                     PostgreSQL 17     data/artifacts/
                                                     (:5432, Flyway)   （成果物ストア）
                                                              ▲
                              GitHub ◀── OAuth（user-to-server 認可）── Spring Boot
```

プロセスとして必要なのは **PostgreSQL と Spring Boot の 2 つだけ**です。
Vite の dev server は開発の利便性（HMR）のためのもので、本番には存在しません。
ジョブキューも DB 上に実装しているため、Redis やメッセージブローカーは不要です。

## 起動方法の 3 パターン

| パターン | 起動するもの | ブラウザで開く URL | 用途 |
| --- | --- | --- | --- |
| A. 分離起動（通常の開発） | `db` コンテナ / `./mvnw spring-boot:run -DskipFrontend=true` / `npm run dev` | `http://localhost:5173` | 画面を触りながら開発する。フロントの変更は即時反映、バックエンドの変更は再起動で反映 |
| B. 同梱起動 | `db` コンテナ / `./mvnw spring-boot:run` | `http://localhost:8080` | 本番に近い形での動作確認。SPA はビルド時点のものが配信されるため、フロントを直したら再ビルドが必要 |
| C. すべてコンテナ | `docker compose --profile full up --build` | `http://localhost:8080` | JDK / Node.js を入れずに動かす、またはデモ用 |

パターン C では `Dockerfile` がマルチステージビルドで jar を作り（ビルドステージで Maven が
Node.js を取得してフロントもビルドする）、JRE だけの実行イメージで起動します。
DB の接続先は `compose.yaml` で `db:5432` に差し替えられ、`app` は `db` のヘルスチェックが
通ってから起動します。`app` に渡る環境変数は `compose.yaml` に列挙したもの
（DB 接続・成果物の保存先・GitHub App の認証情報・`QG_SMTP_HOST` / `QG_SMTP_PORT` / `QG_MAIL_FROM`）だけで、
値はリポジトリ直下の `.env` から Docker Compose が変数展開して渡します。
成果物は `./data/artifacts` にマウントされます。

## フロントエンドとバックエンドの連携

**同一オリジンで動かすことが前提**です（CORS 設定なし、セッション Cookie 認証）。
開発時と本番時で、同一オリジンを実現する方法が異なります。

- **本番・同梱時（パターン B / C）**
  1. Maven の `frontend` プロファイル（`-DskipFrontend` を付けない限り有効）が
     `frontend-maven-plugin` で Node.js を `backend/target/` に取得し、`npm ci` → `npm run build` を実行する
  2. 出力された `frontend/dist/` を `maven-resources-plugin` が `target/classes/static/` にコピーし、jar に含める
  3. Spring Boot が `classpath:/static/` から SPA を配信する。`/runs/xxx` のように
     静的ファイルとして存在しないパスは `SpaForwardingConfig` が `index.html` を返し、
     ルーティングを Vue Router に任せる。ただし `api/` `actuator/` `v3/` `swagger-ui` `badges/` `oauth2/` `login/` `logout` は
     対象外で、存在しない API には正しく 404 を返す

- **開発時（パターン A）**
  - ブラウザは 5173（Vite）だけを見る。`vite.config.ts` の `server.proxy` で、次のパスを 8080 に転送する

    | パス | 用途 |
    | --- | --- |
    | `/api` | REST API |
    | `/oauth2` | ログイン開始（`/oauth2/authorization/github`） |
    | `/login/oauth2` | GitHub からの折り返し（`/login/oauth2/code/github`）。`/login` 全体ではない点に注意（SPA のログイン画面 `/login` は Vite が返す） |
    | `/logout` | ログアウト |
    | `/badges` | バッジ（将来用。現時点でバックエンドに実装はない） |
    | `/actuator` | ヘルスチェックなど |

  - `changeOrigin: false` にしているため、バックエンドから見ても Host は `localhost:5173` のままで、
    セッション Cookie もブラウザには 5173 のものとして保存される。
    本番と同じ「同一オリジン」の条件で認証まわりを確認できる

- **API の型の共有** — バックエンドの springdoc が OpenAPI を生成し（`OpenApiExportIT` が
  `api/openapi.yml` に書き出す）、フロントエンドは `npm run generate:api` で
  `src/api/schema.d.ts` を生成する。画面は `src/api/client.ts` の `openapi-fetch` クライアント
  （`baseUrl: '/'`, `credentials: 'same-origin'`）で呼び出すため、API の変更は型エラーとして検出される

- **画面を開いたときの流れ**
  1. SPA が読み込まれると、Vue Router のガードが `GET /api/v1/me` を呼ぶ
  2. 未ログインならバックエンドは `401` を返す（SPA のシェル自体は認証なしで配信される）。
     フロントは `/login` 画面へ遷移する
  3. ログイン済みならユーザー情報とロール（`ADMIN` / `VIEWER`）が返り、目的の画面を表示する。
     画面側のロール判定は表示の都合だけで、権限の境界はバックエンドの認可が担保する
