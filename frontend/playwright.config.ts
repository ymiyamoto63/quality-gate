import { defineConfig, devices } from '@playwright/test'

// M-10（アクセシビリティ）の計測元。axe-core の critical / serious を 0 件に保つ。
export default defineConfig({
  testDir: './e2e',
  reporter: [['list'], ['json', { outputFile: '../reports/axe-results.json' }]],
  use: {
    baseURL: process.env.QG_E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
