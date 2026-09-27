/**
 * 表示の整形。
 *
 * 値そのものの解釈（合否、差分の良し悪し、判定理由の文言）はサーバが持つ。
 * ここで行うのは「同じ意味の値を同じ見た目にする」ことだけ。
 */

/** 未計測を示す記号。0 と区別がつくよう、空欄にはしない。 */
export const NOT_MEASURED = '—'

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return NOT_MEASURED
  return new Date(value).toLocaleString('ja-JP', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * 計測値。null は未計測であり 0 ではない。
 * 0 を表示してしまうと「計測して 0 だった」と区別がつかなくなる。
 */
export function formatValue(
  value: number | string | null | undefined,
  unit: string | null | undefined,
): string {
  if (value === null || value === undefined) return NOT_MEASURED
  const numeric = typeof value === 'string' ? Number(value) : value
  if (Number.isNaN(numeric)) return NOT_MEASURED
  return `${trimZeros(numeric)}${unitSuffix(unit)}`
}

export function shortSha(commitSha: string | null | undefined): string {
  return commitSha ? commitSha.slice(0, 7) : NOT_MEASURED
}

function unitSuffix(unit: string | null | undefined): string {
  switch (unit) {
    case 'percent':
      return '%'
    case 'count':
      return ' 件'
    case 'ms':
      return 'ms'
    case null:
    case undefined:
    case '':
      return ''
    default:
      return ` ${unit}`
  }
}

/** 82.40 は 82.4、82.00 は 82 と出す。桁が揃わないより、余計な 0 が無いほうが読める。 */
function trimZeros(value: number): string {
  return String(Number(value.toFixed(4)))
}
