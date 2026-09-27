# 認証と認可: 設計

要件は [requirements.md](requirements.md)。GitHub App の作り方は [開発環境](../../development.md#3-ログイン用の-github-app)。

## 1. GitHub App の使い道

quality-gate は GitHub App を 1 つだけ使う（OAuth App は使わない。DD-18）。

| 用途 | 使うもの |
| --- | --- |
| 画面へのログイン | App の Client ID / Client Secret（user-to-server 認可、スコープ `read:user`） |
| 収集ランナーによる対象の読み取り | 同じ App の秘密鍵と、対象リポジトリへのインストール（Contents / Pull requests: Read-only） |
| 違反箇所へのリンク | URL を組み立てるだけ（API は呼ばない） |

収集ランナーからの計測結果の送信には App ではなく Ingest Token を使う（[取り込み](../ingest/design.md#5-ingest-token)）。バックエンドは GitHub API を呼ばない（DD-10）。

## 2. ログインの流れ（`SecurityConfig` の `oauth2Login`）

1. ログイン画面の「GitHub でログイン」が `/oauth2/authorization/github` を開く
2. Spring Security が GitHub の認可画面へリダイレクトする。`redirect_uri` は**ブラウザで開いているオリジン**から組み立てられる
   （5173 の dev server 経由なら `http://localhost:5173/login/oauth2/code/github`）。そのため App の Callback URL には使うオリジンをすべて登録する
3. 利用者が承認すると GitHub が `/login/oauth2/code/github` に折り返し、バックエンドが認可コードをアクセストークンに換えてユーザー情報を取る
4. `AllowlistOAuth2UserService` が GitHub ユーザーを `users`（許可リスト）と照合する
   - 利用者が 0 人なら、最初のユーザーを `ADMIN` として登録する（監査ログに `BOOTSTRAP_ADMIN`）
   - 未登録・無効化されたユーザーは拒否して `/forbidden` へ
5. 成功するとセッションを作って `/` へリダイレクトする

GitHub アカウントを持つ人なら誰でも手順 3 までは進めるため、**手順 4 の許可リストが実質的な入口の制御**になる。Organization のメンバーシップでは制限しない（個人アカウントのため）。

## 3. セッションと認可

| 項目 | 方式 |
| --- | --- |
| セッション | Spring Session JDBC で DB に置く（有効期限 8 時間）。アプリを再起動してもログアウトさせない |
| Cookie | `SESSION`（HttpOnly / SameSite=Lax）。状態を変える操作は CSRF トークン（`XSRF-TOKEN` Cookie を `X-XSRF-TOKEN` ヘッダで送り返す）を求める |
| ロールの反映 | `SessionUserRefreshFilter` がリクエストのたびにセッションのロールを `users` の現在値で置き換える。無効化・許可リストから外れた利用者のセッションは破棄する（最長 8 時間、元の権限で操作できてしまうのを防ぐ） |
| 認可 | `ADMIN` / `VIEWER`。API のメソッドセキュリティ（`@PreAuthorize`）を唯一の権限境界とし、画面のルーティングガードは表示の都合だけ |
| 経路 | Ingest API は Ingest Token、それ以外の `/api/**` はセッション（[アーキテクチャ](../../architecture.md#61-認証の経路)）。再評価（`POST /api/v1/runs/{id}/reevaluate`）は `/api/v1/runs` 配下の POST だが、管理者が画面から行う操作のためセッションで認証する |

## 4. 画面の流れ

1. SPA を開くと、ルーターのガードが `GET /api/v1/me` を呼ぶ（SPA のシェル自体は認証なしで配信する）
2. 未ログインなら 401 が返り、`/login` へ移る
3. ログイン済みならユーザーとロールが返り、目的の画面を出す。ADMIN 専用の画面（`/admin/*`）に VIEWER が来たら `/forbidden` へ移る

| 画面 | 内容 |
| --- | --- |
| `/login` | 「GitHub でログイン」 |
| `/forbidden` | 認証には成功したが許可リストに無い（または無効な）場合。**「ログインに失敗しました」とは出さない**。何度やり直しても状況は変わらないため、「管理者に登録を依頼してください」と取るべき行動を示す |
| 見つからないパス | 「ページが見つかりません」 |
