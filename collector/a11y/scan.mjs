// 収集ランナーの M-10（アクセシビリティ）検査（docs/operations/collector.md 6 章）。
//
// 起動済みの対象アプリの画面を開き、axe-core で検査して、結果を axe-json（analyze() の戻り値の配列）に書き出す。
// 画面はライト・ダークの両方で検査する（配色はテーマごとに別の値で、片方の合格は他方を保証しない）。
//
// 読み込めなかった画面の結果は書き出さない。quality-gate は設定の pages に無い画面を ERROR にするため、
// 検査の失敗が合格に見えることはない。
//
// 環境変数:
//   A11Y_BASE_URL        対象アプリの URL（例: http://127.0.0.1:4173）
//   A11Y_PAGES           検査する画面のパス（空白区切り）
//   A11Y_OUTPUT          書き出すファイル
//   A11Y_READY_SELECTOR  描画が済んだと判断できる要素（任意）。無ければ通信が落ち着くまで待つ
//   A11Y_CHROMIUM        使う Chromium の実行ファイル（任意。Playwright が取得したものを使わない場合）
import { writeFileSync } from 'node:fs'
import { chromium } from 'playwright'
import { AxeBuilder } from '@axe-core/playwright'

// WCAG 2.2 AA（指標仕様書 M-10）。対象の e2e と同じタグにしている
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']
const COLOR_SCHEMES = ['light', 'dark']
const TIMEOUT_MS = 30_000

function required(name) {
  const value = process.env[name]?.trim()
  if (!value) {
    throw new Error(`${name} が指定されていません`)
  }
  return value
}

const baseUrl = required('A11Y_BASE_URL')
const pages = required('A11Y_PAGES').split(/\s+/)
const output = required('A11Y_OUTPUT')
const readySelector = process.env.A11Y_READY_SELECTOR?.trim()
const executablePath = process.env.A11Y_CHROMIUM?.trim() || undefined

const browser = await chromium.launch({ executablePath })
const results = []
let failures = 0
try {
  for (const path of pages) {
    for (const colorScheme of COLOR_SCHEMES) {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      const url = new URL(path, baseUrl).toString()
      try {
        const response = await page.goto(url, { waitUntil: 'networkidle', timeout: TIMEOUT_MS })
        if (!response || !response.ok()) {
          throw new Error(`応答が ${response?.status() ?? 'ありません'}`)
        }
        if (readySelector) {
          await page.locator(readySelector).first().waitFor({ timeout: TIMEOUT_MS })
        }
        const result = await new AxeBuilder({ page }).withTags(TAGS).analyze()
        results.push(result)
        const blocking = result.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious')
        console.error(`${path}（${colorScheme}）: 違反 ${result.violations.length} 件（うち重大 ${blocking.length} 件）`)
      } catch (error) {
        failures++
        console.error(`::warning::${path}（${colorScheme}）を検査できませんでした: ${error.message}`)
      } finally {
        await context.close()
      }
    }
  }
} finally {
  await browser.close()
}

writeFileSync(output, JSON.stringify(results, null, 2))
if (failures > 0) {
  process.exitCode = 1
}
