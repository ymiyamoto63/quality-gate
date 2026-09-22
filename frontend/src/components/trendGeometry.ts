/**
 * トレンドグラフの座標計算。
 *
 * 描画（SVG）から切り離してあるのは、計算だけを単体テストで押さえられるようにするため。
 * 欠測の扱いと目盛りの決め方はこのグラフの正しさそのものであり、
 * 見た目の確認では検証できない。
 */

export interface RawPoint {
  measuredAt: string
  value: number | null
  status: string
  runId: string
  commitSha: string
}

export interface RawSeries {
  seriesId: string
  label: string
  judged: boolean
  colorIndex: number
  points: RawPoint[]
}

export interface PlottedPoint extends RawPoint {
  x: number
  y: number | null
}

export interface PlottedSeries extends RawSeries {
  points: PlottedPoint[]
  /** 欠測で分割された線分。1 本の path にすると、欠測をまたいで線がつながる。 */
  segments: string[]
  /** 直接ラベルを置く位置。最後に値を持つ点。 */
  lastValued: PlottedPoint | null
}

export interface Tick {
  value: number
  y: number
  label: string
}

export interface TimeTick {
  x: number
  label: string
}

export interface Plot {
  series: PlottedSeries[]
  yTicks: Tick[]
  xTicks: TimeTick[]
  thresholdY: number | null
  width: number
  height: number
  padding: { top: number; right: number; bottom: number; left: number }
}

export interface PlotOptions {
  width?: number
  height?: number
  thresholdValue?: number | null
  /** 0 を軸の下端に含めるか。割合（%）では 0 起点にしない */
  includeZero?: boolean
}

// 右の余白は系列の直接ラベル用。しきい値のラベルは作図領域の内側に置く。
const PADDING = { top: 20, right: 92, bottom: 32, left: 56 }

/**
 * 系列を描画座標に写す。
 *
 * y 軸の範囲には**しきい値も含める**。しきい値が範囲外にあると、
 * 合格ラインが画面の外に出て「基準に対してどこにいるか」が読めなくなる。
 */
export function plot(series: RawSeries[], options: PlotOptions = {}): Plot {
  const width = options.width ?? 720
  const height = options.height ?? 280
  const inner = {
    width: width - PADDING.left - PADDING.right,
    height: height - PADDING.top - PADDING.bottom,
  }

  const times = series.flatMap((s) => s.points.map((p) => Date.parse(p.measuredAt)))
  const values = series.flatMap((s) =>
    s.points.map((p) => p.value).filter((v): v is number => v !== null),
  )

  const domain = valueDomain(values, options)
  const timeDomain = spanOf(times)

  const xOf = (time: number) =>
    PADDING.left + ((time - timeDomain.min) / timeDomain.span) * inner.width
  const yOf = (value: number) =>
    PADDING.top + inner.height - ((value - domain.min) / domain.span) * inner.height

  const plotted = series.map((s) => toPlotted(s, xOf, yOf))

  return {
    series: plotted,
    yTicks: ticksOf(domain, yOf),
    xTicks: timeTicksOf(times, xOf),
    thresholdY:
      options.thresholdValue === null || options.thresholdValue === undefined
        ? null
        : yOf(options.thresholdValue),
    width,
    height,
    padding: PADDING,
  }
}

function toPlotted(
  series: RawSeries,
  xOf: (time: number) => number,
  yOf: (value: number) => number,
): PlottedSeries {
  const points = series.points.map((point) => ({
    ...point,
    x: xOf(Date.parse(point.measuredAt)),
    y: point.value === null ? null : yOf(point.value),
  }))

  return {
    ...series,
    points,
    segments: segmentsOf(points),
    lastValued: [...points].reverse().find((p) => p.y !== null) ?? null,
  }
}

/**
 * 欠測で線を切る。
 *
 * 未計測の点をまたいで線をつなぐと、計測していない期間に値があったかのように見える。
 * 逆に 0 を打って線をつなぐと、最悪の値まで落ちたように見える。どちらも嘘になる。
 */
export function segmentsOf(points: PlottedPoint[]): string[] {
  const segments: string[] = []
  let current: PlottedPoint[] = []

  for (const point of points) {
    if (point.y === null) {
      if (current.length > 0) segments.push(pathOf(current))
      current = []
      continue
    }
    current.push(point)
  }
  if (current.length > 0) segments.push(pathOf(current))

  // 前後を欠測に挟まれた孤立点は線にならないため、マーカーだけで表現する
  return segments.filter((d) => d.includes('L'))
}

function pathOf(points: PlottedPoint[]): string {
  return points.map((p, i) => `${i === 0 ? 'M' : 'L'}${round(p.x)},${round(p.y!)}`).join(' ')
}

interface Domain {
  min: number
  max: number
  span: number
}

/**
 * y 軸の範囲。
 *
 * 値の幅に対して余白を取り、上下に張り付かないようにする。割合（%）で
 * 0 起点にしないのは、75%〜95% の変化が潰れて読めなくなるため。
 * ただし**しきい値は必ず範囲に含める**。
 */
function valueDomain(values: number[], options: PlotOptions): Domain {
  const candidates = [...values]
  if (options.thresholdValue !== null && options.thresholdValue !== undefined) {
    candidates.push(options.thresholdValue)
  }
  if (options.includeZero) candidates.push(0)
  if (candidates.length === 0) return { min: 0, max: 1, span: 1 }

  let min = Math.min(...candidates)
  let max = Math.max(...candidates)

  if (min === max) {
    // 値が 1 種類しかない場合。span が 0 だと除算が壊れる
    const pad = Math.abs(min) * 0.1 || 1
    min -= pad
    max += pad
  } else {
    const pad = (max - min) * 0.1
    min -= pad
    max += pad
  }
  if (min < 0 && Math.min(...candidates) >= 0) min = 0

  return { min, max, span: max - min }
}

/** 目盛りの上限。これを超えると軸が線だらけになり、値より目盛りが目立つ。 */
const MAX_Y_TICKS = 7

/**
 * 目盛りはきりのよい数にする。1.37 のような端数は読む手間になる。
 *
 * きりのよい候補を小さいほうから試し、本数が上限に収まる最初のものを採る。
 * 「範囲を 4 等分」のような決め方だと、範囲によっては目盛りが 2 本しか出ず、
 * 点がどのあたりの値なのか読めなくなる。
 */
function ticksOf(domain: Domain, yOf: (value: number) => number): Tick[] {
  const step = niceStep(domain.span)
  const first = Math.ceil(domain.min / step) * step
  const ticks: Tick[] = []

  for (let value = first; value <= domain.max + 1e-9; value += step) {
    const rounded = Number(value.toFixed(6))
    ticks.push({ value: rounded, y: yOf(rounded), label: formatTick(rounded) })
  }
  return ticks
}

function niceStep(span: number): number {
  if (span <= 0) return 1
  const magnitude = 10 ** Math.floor(Math.log10(span))

  for (const multiplier of [0.1, 0.2, 0.25, 0.5, 1, 2, 2.5, 5, 10]) {
    const step = multiplier * magnitude
    if (span / step <= MAX_Y_TICKS) {
      return step
    }
  }
  return magnitude * 10
}

function formatTick(value: number): string {
  return Number.isInteger(value) ? value.toLocaleString('ja-JP') : String(value)
}

/** 日付の目盛り。点が増えても最大 5 本に抑える。 */
function timeTicksOf(times: number[], xOf: (time: number) => number): TimeTick[] {
  const unique = [...new Set(times)].sort((a, b) => a - b)
  if (unique.length === 0) return []

  const maxTicks = 5
  const stride = Math.max(1, Math.ceil(unique.length / maxTicks))
  const picked = unique.filter((_, i) => i % stride === 0)
  if (picked.at(-1) !== unique.at(-1)) picked.push(unique.at(-1)!)

  return picked.map((time) => ({
    x: xOf(time),
    label: new Date(time).toLocaleDateString('ja-JP', { month: 'numeric', day: 'numeric' }),
  }))
}

function spanOf(times: number[]): { min: number; span: number } {
  if (times.length === 0) return { min: 0, span: 1 }
  const min = Math.min(...times)
  const max = Math.max(...times)
  // 点が 1 つだけのときは中央に置く
  return { min, span: max === min ? 1 : max - min }
}

function round(value: number): number {
  return Math.round(value * 100) / 100
}
