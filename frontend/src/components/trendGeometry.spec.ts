import { describe, it, expect } from 'vitest'
import { plot, type RawSeries } from './trendGeometry'

function series(values: (number | null)[], overrides: Partial<RawSeries> = {}): RawSeries {
  return {
    seriesId: 'backend',
    label: 'backend',
    colorIndex: 0,
    points: values.map((value, i) => ({
      measuredAt: `2026-09-${String(10 + i).padStart(2, '0')}T00:00:00Z`,
      value,
      status: value === null ? 'SKIP' : 'PASS',
      runId: `run-${i}`,
      commitSha: `sha${i}`,
    })),
    ...overrides,
  }
}

describe('plot', () => {
  it('欠測で線を切る', () => {
    const result = plot([series([80, null, 90])])

    // 未計測をまたいで線をつなぐと、計測していない期間に値があったように見える
    expect(result.series[0]!.segments).toHaveLength(0)
    expect(result.series[0]!.points[1]!.y).toBeNull()
  })

  it('欠測を挟まない区間は 1 本の線にする', () => {
    const result = plot([series([80, 85, 90])])

    expect(result.series[0]!.segments).toHaveLength(1)
    expect(result.series[0]!.segments[0]!).toMatch(/^M[\d.]+,[\d.]+ L/)
  })

  it('欠測の前後にそれぞれ線を引く', () => {
    const result = plot([series([80, 85, null, 90, 95])])

    expect(result.series[0]!.segments).toHaveLength(2)
  })

  it('値の大きいほど上に来る', () => {
    const result = plot([series([80, 90])])
    const low = result.series[0]!.points[0]!
    const high = result.series[0]!.points[1]!

    // SVG の y 軸は下向きなので、値が大きいほど y は小さい
    expect(high.y!).toBeLessThan(low.y!)
  })

  it('しきい値を必ず軸の範囲に含める', () => {
    // 値は 90 前後だが、しきい値は 40。範囲外だと合格ラインが画面の外に出る
    const result = plot([series([90, 92])], { thresholdValue: 40 })

    expect(result.thresholdY).not.toBeNull()
    expect(result.thresholdY!).toBeGreaterThan(result.padding.top)
    expect(result.thresholdY!).toBeLessThan(result.height - result.padding.bottom)
  })

  it('しきい値がなければ線を引かない', () => {
    expect(plot([series([80, 90])]).thresholdY).toBeNull()
  })

  it('値が 1 種類でも座標を計算できる', () => {
    // span が 0 だと除算が壊れる
    const result = plot([series([80, 80, 80])])

    expect(result.series[0]!.points.every((p) => Number.isFinite(p.y!))).toBe(true)
  })

  it('点が 1 つでも座標を計算できる', () => {
    const result = plot([series([80])])

    expect(Number.isFinite(result.series[0]!.points[0]!.x)).toBe(true)
    expect(Number.isFinite(result.series[0]!.points[0]!.y!)).toBe(true)
  })

  it('計測がなければ空の結果を返す', () => {
    const result = plot([])

    expect(result.series).toHaveLength(0)
    expect(result.xTicks).toHaveLength(0)
  })

  it('全点が欠測でも壊れない', () => {
    const result = plot([series([null, null])])

    expect(result.series[0]!.segments).toHaveLength(0)
    expect(result.yTicks.length).toBeGreaterThan(0)
  })

  it('目盛りはきりのよい数にする', () => {
    const result = plot([series([0, 97])], { includeZero: true })

    // 1.37 のような端数は読む手間になる
    for (const tick of result.yTicks) {
      expect(tick.value % 5).toBe(0)
    }
  })

  it('日付の目盛りを 5 本以下に抑える', () => {
    const many = Array.from({ length: 20 }, (_, i) => 80 + i)
    const result = plot([series(many)])

    expect(result.xTicks.length).toBeLessThanOrEqual(6)
    // 最後の日付は必ず出す
    expect(result.xTicks.at(-1)!.x).toBeCloseTo(result.width - result.padding.right, 1)
  })

  it('直接ラベルの位置は最後に値を持つ点にする', () => {
    const result = plot([series([80, 90, null])])

    // 欠測の点にラベルを置くと、値のない場所に数字が出る
    expect(result.series[0]!.lastValued!.value).toBe(90)
  })

  it('割合では 0 起点にしない', () => {
    // 75〜95% の変化が潰れて読めなくなる
    const result = plot([series([75, 95])])

    expect(Math.min(...result.yTicks.map((t) => t.value))).toBeGreaterThan(0)
  })

  it('負の値がなければ軸を負に伸ばさない', () => {
    const result = plot([series([1, 2])])

    expect(result.yTicks.every((t) => t.value >= 0)).toBe(true)
  })

  it('目盛りが少なすぎない', () => {
    // 「範囲を n 等分」で決めると、範囲によっては目盛りが 2 本しか出ず、
    // 点がどのあたりの値なのか読めなくなる
    const result = plot([series([45, 95])])

    expect(result.yTicks.length).toBeGreaterThanOrEqual(4)
  })

  it('目盛りが多すぎない', () => {
    const result = plot([series([0, 1_000_000])], { includeZero: true })

    expect(result.yTicks.length).toBeLessThanOrEqual(8)
  })

  it('小さい値の範囲でも目盛りを刻める', () => {
    // 件数 0〜3 件のような小さい範囲
    const result = plot([series([0, 1, 2, 3])], { includeZero: true })

    expect(result.yTicks.length).toBeGreaterThanOrEqual(3)
    expect(result.yTicks.every((t) => Number.isFinite(t.y))).toBe(true)
  })
})
