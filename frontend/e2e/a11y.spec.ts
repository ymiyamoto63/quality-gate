import { test, expect } from '@playwright/test'
import { COLOR_SCHEMES, expectNoBlockingViolations } from './axe'

/**
 * アクセシビリティ検査。ログイン不要の画面。
 *
 * ログインが要る画面は authenticated-a11y.spec.ts で検査する。
 */
for (const scheme of COLOR_SCHEMES) {
  test(`ログイン（${scheme}）に重大なアクセシビリティ違反がない`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme })
    // 未ログイン（/api/v1/me が 401）として描く
    await page.route('**/api/v1/**', (route) => route.fulfill({ status: 401, json: {} }))
    await page.goto('/login')
    await expect(page.getByLabel('パスワード')).toBeVisible()

    await expectNoBlockingViolations(page)
  })
}
