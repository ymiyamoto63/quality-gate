# 認証と GitHub App

quality-gate は GitHub App を **「ログイン手段」として使います**（OAuth App は使いません。D-11）。
計測データを GitHub から取りに行くことはなく、計測結果は CI が Ingest API で送ってきます。

| 用途 | 使うもの | 状態 |
| --- | --- | --- |
| 画面へのログイン | GitHub App の Client ID / Client Secret（user-to-server 認可、スコープ `read:user`） | 実装済み |
| CI からの計測結果の送信 | GitHub App ではなく **Ingest Token**（quality-gate が発行する Bearer トークン。管理 › リポジトリ管理で発行） | 実装済み |
| 違反箇所へのリンク | `https://github.com/<owner>/<repo>/blob/<sha>/<path>#L<n>` を組み立てるだけ（API 呼び出しなし） | 実装済み |
| リポジトリ内容の読み取り | GitHub App の Contents: Read-only 権限 | 将来用。現時点では不要 |

## ログインの流れ（`SecurityConfig` の `oauth2Login`）

1. ログイン画面の「GitHub でログイン」が `/oauth2/authorization/github` を開く
2. Spring Security が GitHub の認可画面へリダイレクトする。このとき `redirect_uri` は
   **ブラウザで開いているオリジン**から組み立てられる（5173 経由なら `http://localhost:5173/login/oauth2/code/github`）。
   そのため GitHub App の Callback URL には 8080 と 5173 の両方を登録する
3. 利用者が承認すると GitHub が `/login/oauth2/code/github` に折り返し、
   バックエンドが認可コードを Client Secret と引き換えにアクセストークンへ交換し、GitHub のユーザー情報を取得する
4. `AllowlistOAuth2UserService` が GitHub ユーザーを `users` テーブル（許可リスト）と照合する
   - 利用者が 0 件なら、最初のユーザーを `ADMIN` として登録する（初回のみ）
   - 未登録・無効化されたユーザーは拒否して `/forbidden` へ
5. 成功するとセッションを作成して `/` へリダイレクトする。セッションは Spring Session JDBC で
   **DB に保存**される（有効期限 8 時間）ため、バックエンドを再起動してもログイン状態は保たれる

GitHub アカウントを持つ人なら誰でも手順 1〜3 までは進めるため、**手順 4 の許可リストが実質的なアクセス制御**です。
Organization のメンバーシップによる制限は行っていません。

## 認証の経路

認証の経路は 2 つに分かれています（`SecurityConfig`）。

| 経路 | 対象 | 認証 | CSRF |
| --- | --- | --- | --- |
| Ingest | `POST /api/v1/runs/**`（`/reevaluate` を除く）と `GET /api/v1/runs/{id}/status` | `Authorization: Bearer qg_<prefix>_<secret>`（リポジトリ単位の Ingest Token） | 無効（Cookie を使わない） |
| 画面 | それ以外の `/api/**` | GitHub ログインのセッション Cookie | 有効（`XSRF-TOKEN` Cookie の値を `X-XSRF-TOKEN` ヘッダで送り返す） |

再評価（`POST /api/v1/runs/{id}/reevaluate`）は `/api/v1/runs` 配下の POST ですが、管理者が画面から行う操作のため画面の経路で認証します。
Ingest Token では参照 API を呼べません。
