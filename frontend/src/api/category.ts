import type { DisplayStatus, MeasurementStatus } from './status'

/**
 * 指標をカテゴリ（機能テスト、セキュリティなど 6 つ）ごとにまとめた要約。
 *
 * 経営陣が「どの分野に問題があるか」を一目で掴めるようにするための、表示だけの集計。
 * 合否そのものはサーバが指標ごとに決めており、ここではそれを分野ごとに寄せ集めるだけ。
 */

/** 表示順。サーバの MetricCategory の宣言順（docs/metrics.md 1 章）と揃える。 */
export const CATEGORY_ORDER = [
  '機能テスト',
  '性能テスト',
  'セキュリティ',
  'コード構造',
  '契約・互換性',
  '使いやすさ',
] as const

export interface CategoryRow {
  category: string
  status: MeasurementStatus
}

export interface CategorySummary {
  category: string
  status: DisplayStatus
  /** 判定に使った項目の数（コンポーネント・条件ごとに 1 項目）。 */
  judged: number
  failed: number
  errored: number
}

/**
 * 6 カテゴリすべてを決まった順で返す。判定した項目が無いカテゴリは「未計測」として残す。
 * 消してしまうと、計測していない分野があることに気付けないため。
 *
 * カテゴリの状態は、不合格が 1 つでもあれば不合格、次に計測エラーがあれば計測エラー、
 * それ以外は合格とする。不合格を優先するのは、品質の問題のほうが先に対処すべきものだから。
 */
export function summarizeByCategory(rows: readonly CategoryRow[]): CategorySummary[] {
  const known: readonly string[] = CATEGORY_ORDER
  const extra = [...new Set(rows.map((row) => row.category))].filter((c) => !known.includes(c))
  return [...CATEGORY_ORDER, ...extra].map((category) => {
    const inCategory = rows.filter((row) => row.category === category)
    const failed = inCategory.filter((row) => row.status === 'FAIL').length
    const errored = inCategory.filter((row) => row.status === 'ERROR').length
    const status: DisplayStatus =
      inCategory.length === 0
        ? 'NOT_MEASURED'
        : failed > 0
          ? 'FAIL'
          : errored > 0
            ? 'ERROR'
            : 'PASS'
    return { category, status, judged: inCategory.length, failed, errored }
  })
}

/** カテゴリのカードに添える一文。 */
export function categoryDetail(summary: CategorySummary): string {
  if (summary.judged === 0) return '判定した項目はありません'
  const problems = [
    summary.failed > 0 ? `${summary.failed} 項目が不合格` : '',
    summary.errored > 0 ? `${summary.errored} 項目が計測エラー` : '',
  ].filter(Boolean)
  return problems.length === 0
    ? `${summary.judged} 項目すべて合格`
    : `${summary.judged} 項目中 ${problems.join('、')}`
}
