import { defineConfig, devices } from '@playwright/test'

// アクセシビリティ検査（PR の CI）。axe-core の critical / serious を 0 件に保つ。
export default defineConfig({
  testDir: './e2e',
  reporter: 'list',
  use: {
    baseURL: process.env.QG_E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
  },
  // 検査先を指定しなければ dev server を起動する。起動済みならそれを使う。
  // CI には dev server を立てる手順が無く、これが無いと検査が接続エラーで落ちる
  webServer: process.env.QG_E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run dev -- --port 5173 --strictPort',
        url: 'http://localhost:5173',
        reuseExistingServer: true,
      },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // 実行環境に導入済みの Chromium を使う場合に指定する。
        // Playwright が同梱版を取りに行けない環境（社内プロキシ配下など）でも動かすため。
        launchOptions: process.env.QG_E2E_CHROMIUM
          ? { executablePath: process.env.QG_E2E_CHROMIUM }
          : {},
      },
    },
  ],
})
