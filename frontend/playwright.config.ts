import { defineConfig, devices } from '@playwright/test'

// M-09（アクセシビリティ）の計測元。axe-core の critical / serious を 0 件に保つ。
export default defineConfig({
  testDir: './e2e',
  // axe-core の結果（M-09 の成果物）は reports/axe-results.json に書き出す（e2e/axe-report.ts）。
  // テストレポートを同じ名前で出すと、axe の結果と取り違えて送ってしまう
  reporter: [['list'], ['json', { outputFile: '../reports/playwright-results.json' }]],
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
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
