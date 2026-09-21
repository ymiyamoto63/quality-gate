import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

/**
 * M-10（アクセシビリティ）の計測元。
 *
 * 判定対象は impact が critical / serious のもの。serious には
 * キーボード操作不能やコントラスト不足といった、実際に利用を妨げる違反が含まれる。
 *
 * 自動検査で検出できる WCAG 違反は一部にとどまるため、
 * 「重大 0 件」は AA 適合の必要条件であって十分条件ではない。
 */
const PAGES = [
  { path: '/login', name: 'ログイン' },
  { path: '/forbidden', name: 'アクセス拒否' },
]

for (const page of PAGES) {
  test(`${page.name} に重大なアクセシビリティ違反がない`, async ({ page: browserPage }) => {
    await browserPage.goto(page.path)

    const results = await new AxeBuilder({ page: browserPage })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()

    const blocking = results.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious',
    )
    expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([])
  })
}
