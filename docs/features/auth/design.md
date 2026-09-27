# 認証: 設計

要件は [requirements.md](requirements.md)。経路の一覧は [アーキテクチャ](../../architecture.md#61-認証の経路)。

## 1. 画面のログイン

- Spring Security のフォームログイン。`POST /api/v1/login`（`username` / `password`、`application/x-www-form-urlencoded`）に成功すると 204、失敗すると 401。`POST /api/v1/logout` は 204
- 利用者は `InMemoryUserDetailsManager` の 1 人だけ。パスワードは起動時にハッシュにして持つ
- セッションはサーバのメモリに持つ（`JSESSIONID`、HttpOnly、有効期間 8 時間）。アプリを再起動するとログインし直しになる
- CSRF はログインも含めて有効。画面は先に `/api/v1/me` を GET して `XSRF-TOKEN` Cookie を受け取り、`X-XSRF-TOKEN` ヘッダで送り返す
- `/api/**` だけを認証必須にし、SPA のシェルは未ログインでも返す。画面は `/api/v1/me` の 401 を受けて `/login?next=<開こうとした URL>` へ移る。`next` は同じサイトのパスだけを受け付ける

## 2. 取り込み API

`POST /api/v1/runs/**` は `Authorization: Bearer <Ingest Token>` で認証する（ステートレス、CSRF 対象外）。トークンは `QG_INGEST_TOKEN`（交換中は新旧をカンマ区切り）。

## 3. 採らなかった方式

| 方式 | 採らなかった理由 |
| --- | --- |
| GitHub でのログインと許可リスト | 経営陣が GitHub のアカウントを持っていないと見られない。許可リストの管理画面も要る |
| 利用者ごとのアカウントとロール | 画面は見るだけで、権限を分ける操作が無い。誰が見たかの記録も求めない |
| ログインなし（ネットワークだけで守る） | 脆弱性の詳細などが社内の誰にでも見える。共有のパスワードで足りる |
