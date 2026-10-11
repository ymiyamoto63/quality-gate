import { test, expect, type Page } from '@playwright/test'
import { COLOR_SCHEMES, expectNoBlockingViolations } from './axe'
import releaseReport from './fixtures/release-report.json' with { type: 'json' }
import releaseHistory from './fixtures/release-history.json' with { type: 'json' }
import metricTrends from './fixtures/metric-trends.json' with { type: 'json' }

/**
 * ログインが要る画面（リリース判定・指標の推移）のアクセシビリティ検査。
 *
 * 応答例は結合テスト（ReleaseReportApiIT）が実物の API から書き出したものを使う。
 * 手で書いた例だと、API が変わっても検査は通り続け、実際の画面だけが壊れる。
 */
async function stubApi(page: Page): Promise<void> {
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/me') return route.fulfill({ json: { username: 'quality' } })
    if (path === '/api/v1/release') return route.fulfill({ json: releaseReport })
    if (path === '/api/v1/release/history') return route.fulfill({ json: releaseHistory })
    if (path === '/api/v1/release/trends') return route.fulfill({ json: metricTrends })
    // 一致しない API は 404 にし、検査中の取りこぼしを目立たせる
    return route.fulfill({ status: 404, json: {} })
  })
}

for (const scheme of COLOR_SCHEMES) {
  test(`リリース判定（${scheme}）に重大なアクセシビリティ違反がない`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme })
    await stubApi(page)
    await page.goto('/?ref=v1.2.0')

    // 検査前に中身が描かれていることを確かめる。空のページは必ず「違反 0 件」になる
    await expect(page.getByRole('heading', { name: 'リリース不可' })).toBeVisible()
    await expect(page.getByText('判定の履歴')).toBeVisible()
    // 判定の対象外の指標と、既定より緩めた合格ラインの印も検査に含める
    await expect(page.getByRole('heading', { name: /判定の対象外/ })).toBeVisible()
    await expect(page.getByText(/既定より緩い/).first()).toBeVisible()

    // 折りたたまれた中身（主な違反・技術的な定義）も開いて検査する
    for (const summary of await page.locator('details > summary').all()) await summary.click()

    await expectNoBlockingViolations(page)
  })
}

for (const scheme of COLOR_SCHEMES) {
  test(`指標の推移（${scheme}）に重大なアクセシビリティ違反がない`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme })
    await stubApi(page)
    await page.goto('/trends')

    await expect(page.getByRole('heading', { name: 'ブランチカバレッジ' })).toBeVisible()

    // 表も開き、点にフォーカスしてツールチップを出した状態で検査する
    for (const summary of await page.locator('details > summary').all()) await summary.click()
    await page.locator('.qg-trend__point').first().focus()
    await expect(page.locator('.qg-trend__tooltip')).toBeVisible()

    await expectNoBlockingViolations(page)
  })
}
