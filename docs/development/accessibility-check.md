# アクセシビリティ検査（M-10）

```bash
cd frontend && npm run test:a11y      # ライト / ダークの両モードで検査（playwright test）
```

dev server は起動していなければ自動で立ち上がります（起動済みならそれを使います）。
検査先を変える場合は `QG_E2E_BASE_URL` を、同梱ブラウザを取得できない環境では
`QG_E2E_CHROMIUM` に Chromium の実行ファイルのパスを渡してください。

Pull Request の CI（`ci.yml` の `accessibility` ジョブ）でも実行し、critical / serious の違反があれば失敗させます。

axe-core の結果はリポジトリ直下の `reports/axe-results.json` に書き出されます（CI では失敗したときに成果物として残す）。同じ場所の `playwright-results.json` はテストレポートで、
axe の結果ではありません。検査する画面を足したら `.quality-gate.yml` の
`accessibility.pages` にも足してください。
