import { test } from '@playwright/test'
import { COLOR_SCHEMES, expectNoBlockingViolations } from './axe'

/**
 * M-10（アクセシビリティ）の計測元。ログイン不要の画面。
 *
 * ログインが要る画面は authenticated-a11y.spec.ts で検査する。
 */
const PAGES = [
  { path: '/login', name: 'ログイン' },
  { path: '/forbidden', name: 'アクセス拒否' },
]

for (const target of PAGES) {
  for (const scheme of COLOR_SCHEMES) {
    test(`${target.name}（${scheme}）に重大なアクセシビリティ違反がない`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme })
      await page.goto(target.path)

      await expectNoBlockingViolations(page)
    })
  }
}
