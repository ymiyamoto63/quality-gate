/**
 * 指標の推移グラフの目盛りと座標の計算（docs/features/trends/design.md）。
 *
 * 描画（SVG）から切り離し、数値の扱いだけを単体テストで確かめられるようにする。
 */

/** 縦軸。0 から始める。0 から始めないと小さな揺れが大きく見え、悪化を読み違える。 */
export interface Axis {
  max: number
  ticks: number[]
}

/**
 * 縦軸の上端と目盛りを決める。
 *
 * @param fixedMax サーバが固定した上端（% の指標は 100）。null なら値と合格ラインの最大から決める
 * @param values 値と合格ライン。null（計測エラーなど）は無視する
 * @param integer 件数のように整数しか取らない値か。目盛りに 0.5 件のような値を出さない
 */
export function axisOf(
  fixedMax: number | null,
  values: Array<number | null>,
  integer = false,
): Axis {
  if (fixedMax !== null && fixedMax > 0) {
    return { max: fixedMax, ticks: ticksOf(fixedMax, niceStep(fixedMax / 4)) }
  }
  const largest = Math.max(
    0,
    ...values.filter((v): v is number => v !== null && Number.isFinite(v)),
  )
  // ずっと 0 でも線が軸に重ならないよう、上端は 0 より上に取る
  const raw = niceStep(Math.max(largest, integer ? 1 : 0) / 4)
  const step = integer ? Math.max(1, Math.ceil(raw)) : raw
  const max = Math.max(step, Math.ceil(largest / step) * step)
  return { max, ticks: ticksOf(max, step) }
}

/** 1・2・2.5・5 の 10 のべき乗倍のうち、raw 以上で最小の刻み。 */
export function niceStep(raw: number): number {
  if (raw <= 0) return 1
  const power = 10 ** Math.floor(Math.log10(raw))
  const fraction = raw / power
  const nice =
    fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 2.5 ? 2.5 : fraction <= 5 ? 5 : 10
  return nice * power
}

function ticksOf(max: number, step: number): number[] {
  const ticks: number[] = []
  for (let value = 0; value <= max + step / 1000; value += step) {
    ticks.push(Number(value.toFixed(10)))
  }
  return ticks
}

/** 計測の位置（0 始まり）を横の座標にする。計測が 1 件なら中央に置く。 */
export function xOf(index: number, count: number, left: number, width: number): number {
  if (count <= 1) return left + width / 2
  return left + (index * width) / (count - 1)
}

export interface Located {
  runIndex: number
  y: number | null
}

/**
 * 折れ線を、値が続いている区間ごとに分ける。
 *
 * 計測エラー・対象外（y が null）や、その指標を判定していない計測では線を途切れさせる。
 * 前後をつなぐと、測れていない期間があったことが見えなくなる。
 */
export function segmentsOf(points: Located[]): Located[][] {
  const segments: Located[][] = []
  let current: Located[] = []
  let previousIndex = -2
  for (const point of [...points].sort((a, b) => a.runIndex - b.runIndex)) {
    const continues = point.y !== null && point.runIndex === previousIndex + 1
    if (point.y === null) {
      if (current.length > 0) segments.push(current)
      current = []
    } else if (continues) {
      current.push(point)
    } else {
      if (current.length > 0) segments.push(current)
      current = [point]
    }
    previousIndex = point.runIndex
  }
  if (current.length > 0) segments.push(current)
  return segments
}

/**
 * 計測ごとの合格ラインを階段状の線にする（SVG の path）。
 *
 * 合格ラインは計測ごとに判定時の値が焼き付いている。途中で変えた場合も、過去の点の合否が
 * 当時の合格ラインと食い違って見えないよう、計測ごとの値で引く。各計測の段は前後の計測との中間まで伸ばす。
 * 合格ラインの無い計測では線を切る。
 */
export function stepPathOf(
  steps: Array<{ x: number; y: number | null }>,
  left: number,
  right: number,
): string {
  const commands: string[] = []
  let drawing = false
  steps.forEach((step, i) => {
    if (step.y === null) {
      drawing = false
      return
    }
    const prev = steps[i - 1]
    const next = steps[i + 1]
    const start = prev ? (prev.x + step.x) / 2 : left
    const end = next ? (step.x + next.x) / 2 : right
    commands.push(drawing ? `V${round(step.y)}` : `M${round(start)} ${round(step.y)}`)
    commands.push(`H${round(end)}`)
    drawing = true
  })
  return commands.join(' ')
}

function round(value: number): number {
  return Math.round(value * 100) / 100
}
