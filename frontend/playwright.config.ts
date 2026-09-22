import { defineConfig, devices } from '@playwright/test'

// M-10（アクセシビリティ）の計測元。axe-core の critical / serious を 0 件に保つ。
export default defineConfig({
  testDir: './e2e',
  reporter: [['list'], ['json', { outputFile: '../reports/axe-results.json' }]],
  use: {
    baseURL: process.env.QG_E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
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
