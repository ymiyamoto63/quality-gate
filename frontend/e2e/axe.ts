import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { writeAxeResult } from './axe-report'

/**
 * 検査するテーマ。
 *
 * ライトだけを検査すると、ダーク面のコントラスト不足を取りこぼす。
 * 配色はテーマごとに別の値であり、片方が通っても他方の保証にはならない。
 * 実際、ダーク面のリンク色（ブラウザ既定の #0000EE）は 1.9:1 しかなかった。
 */
export const COLOR_SCHEMES = ['light', 'dark'] as const

/**
 * M-10 の判定対象となる違反が 0 件であることを検査する。
 *
 * impact が critical / serious のものに絞る。serious にはキーボード操作不能や
 * コントラスト不足といった、実際に利用を妨げる違反が含まれる。
 *
 * 自動検査で検出できる WCAG 違反は一部にとどまるため、
 * 「重大 0 件」は AA 適合の必要条件であって十分条件ではない。
 *
 * 結果は判定の前に書き出す。違反があって検査が落ちたときこそ、
 * その違反を quality-gate に届ける必要がある。
 */
export async function expectNoBlockingViolations(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  writeAxeResult(test.info().testId, results)

  const blocking = results.violations.filter(
    (v) => v.impact === 'critical' || v.impact === 'serious',
  )
  // 失敗時に違反の中身がそのまま読めるようにする
  expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([])
}
