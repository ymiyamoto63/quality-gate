import { test, expect, type Page } from '@playwright/test'
import { COLOR_SCHEMES, expectNoBlockingViolations } from './axe'
import runDetail from './fixtures/run-detail.json' with { type: 'json' }
import findings from './fixtures/findings.json' with { type: 'json' }
import trend from './fixtures/trend.json' with { type: 'json' }

/**
 * ログインが要る画面のアクセシビリティ検査（M-10）。
 *
 * 応答例は結合テスト（RunQueryApiIT）が実物の API から書き出したものを使う。
 * 手で書いた例だと、API が変わっても検査は通り続け、実際の画面だけが壊れる。
 */
const RUN_ID = runDetail.runId
const REPOSITORY_ID = runDetail.repository.repositoryId

async function stubApi(page: Page): Promise<void> {
  await page.route('**/api/v1/me', (route) =>
    route.fulfill({
      json: {
        userId: '00000000-0000-0000-0000-000000000001',
        githubLogin: 'ymiyamoto63',
        displayName: '検査用',
        avatarUrl: null,
        role: 'VIEWER',
      },
    }),
  )
  await page.route('**/api/v1/runs/*/findings*', (route) => route.fulfill({ json: findings }))
  await page.route('**/api/v1/runs/*', (route) => route.fulfill({ json: runDetail }))
  // ページの URL 自体と紛れないよう、API のパスまで含めて絞る
  await page.route('**/api/v1/repositories/*/trends*', (route) => route.fulfill({ json: trend }))
}

const PAGES = [
  { path: `/runs/${RUN_ID}`, name: 'Run 詳細', expected: '重大・高 脆弱性件数' },
  { path: `/runs/${RUN_ID}/findings`, name: '違反一覧', expected: 'critical-lib' },
  {
    path: `/repositories/${REPOSITORY_ID}/trends`,
    name: 'トレンド',
    expected: 'ブランチカバレッジ の推移',
  },
]

for (const target of PAGES) {
  for (const scheme of COLOR_SCHEMES) {
    test(`${target.name}（${scheme}）に重大なアクセシビリティ違反がない`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme })
      await stubApi(page)
      await page.goto(target.path)

      // 検査前に中身が描かれていることを確かめる。空のページは必ず「違反 0 件」になる
      await expect(page.getByText(target.expected).first()).toBeVisible()

      await expectNoBlockingViolations(page)
    })
  }
}
