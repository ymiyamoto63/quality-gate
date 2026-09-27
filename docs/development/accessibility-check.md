# アクセシビリティ検査

```bash
cd frontend && npm run test:a11y      # ライト / ダークの両モードで検査（playwright test）
```

dev server は起動していなければ自動で立ち上がります（起動済みならそれを使います）。
検査先を変える場合は `QG_E2E_BASE_URL` を、同梱ブラウザを取得できない環境では
`QG_E2E_CHROMIUM` に Chromium の実行ファイルのパスを渡してください。

Pull Request の CI（`ci.yml` の `accessibility` ジョブ）でも実行し、critical / serious の違反があれば失敗させます。
違反の内容は失敗のメッセージ（CI ではジョブのログ）に出ます。
