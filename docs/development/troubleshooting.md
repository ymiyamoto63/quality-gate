# 起動時のよくある症状

| 症状 | 原因と対処 |
| --- | --- |
| ヘッダーだけ表示され本文が空のまま。dev server に `http proxy error: /api/v1/me` / `connect ETIMEDOUT 127.0.0.1:8080` | バックエンドに到達できていない。バックエンドが起動しているか確認する。WSL2 では Vite とバックエンドを**同じ環境**（両方 WSL 内、または両方 Windows 側）で動かす。Windows 側の IDE でバックエンドを動かす場合は `.wslconfig` に `networkingMode=mirrored` を設定する |
| 「GitHub でログイン」を押すと GitHub の 404 になり、URL に `client_id=placeholder-client-id` が含まれる | `QG_GITHUB_CLIENT_ID` / `QG_GITHUB_CLIENT_SECRET` が読み込まれていない。[セットアップ](setup.md#ログイン用の-github-app)の手順で GitHub App を作成し、リポジトリ直下の `.env` に書いてバックエンドを再起動する |
| バックエンドの起動時に `Client id of registration 'github' must not be empty` で失敗する | `.env` に `QG_GITHUB_CLIENT_ID=` のように空の値が残っている。空の値は既定値より優先されるため、値を入れるか行ごと削除する |
| GitHub で `redirect_uri is not associated with this application` と表示される | GitHub App の Callback URL が、URL 中の `redirect_uri` と一致していない。表示された `redirect_uri` をそのまま Callback URL に追加する |

疎通は Vite を動かしているのと同じ端末から `curl http://127.0.0.1:8080/actuator/health` で確認できます。
