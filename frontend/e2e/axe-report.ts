import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * axe-core の結果を quality-gate に送る形（axe-json）にまとめる。
 *
 * 検査は並列に走るため、1 検査 1 ファイルで書き出し、最後に配列へ束ねる。
 * 束ねたファイルが M-10 の成果物になる（docs/initial/02-metrics-spec.md M-10）。
 */
const REPORTS_DIR = fileURLToPath(new URL('../../reports/', import.meta.url))

/** 検査ごとの結果の置き場。 */
export const AXE_RESULTS_DIR = join(REPORTS_DIR, 'axe')

/** 送信する成果物。 */
export const AXE_RESULTS_FILE = join(REPORTS_DIR, 'axe-results.json')

/**
 * 前回の結果を消す。残っていると、今回検査しなかった画面の結果まで
 * 混ざって送られ、検査したことになってしまう。
 */
export function resetAxeResults(): void {
  rmSync(AXE_RESULTS_DIR, { recursive: true, force: true })
  rmSync(AXE_RESULTS_FILE, { force: true })
  mkdirSync(AXE_RESULTS_DIR, { recursive: true })
}

export function writeAxeResult(name: string, result: unknown): void {
  mkdirSync(AXE_RESULTS_DIR, { recursive: true })
  writeFileSync(join(AXE_RESULTS_DIR, `${name}.json`), JSON.stringify(result))
}

/** ファイル名順に束ねる。実行順に依存させず、同じ検査なら同じ成果物にする。 */
export function mergeAxeResults(): void {
  const files = readdirSync(AXE_RESULTS_DIR)
    .filter((file) => file.endsWith('.json'))
    .sort()
  const results = files.map((file) => JSON.parse(readFileSync(join(AXE_RESULTS_DIR, file), 'utf8')))
  writeFileSync(AXE_RESULTS_FILE, JSON.stringify(results, null, 2))
}
