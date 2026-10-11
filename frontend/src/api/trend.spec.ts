import { describe, it, expect } from 'vitest'
import { axisOf, niceStep, segmentsOf, stepPathOf, xOf } from './trend'

describe('axisOf', () => {
  it('上端が固定されていればそのまま使う（% の指標は 0〜100）', () => {
    expect(axisOf(100, [85, 75])).toEqual({ max: 100, ticks: [0, 25, 50, 75, 100] })
  })

  it('固定しなければ値と合格ラインの最大を切りのよい数に丸める', () => {
    expect(axisOf(null, [320, 410, 500])).toEqual({ max: 600, ticks: [0, 200, 400, 600] })
  })

  it('小数の合格ライン（エラー率 0.1%）でも線が底に張りつかない', () => {
    expect(axisOf(null, [0.02, 0.1]).max).toBeCloseTo(0.1)
  })

  it('ずっと 0 件でも上端は 1 以上にし、件数の目盛りは整数にする', () => {
    expect(axisOf(null, [0, 0, null], true)).toEqual({ max: 1, ticks: [0, 1] })
    expect(axisOf(null, [3, 0], true).ticks).toEqual([0, 1, 2, 3])
  })
})

describe('niceStep', () => {
  it('1・2・2.5・5 の倍数に切り上げる', () => {
    expect(niceStep(0.3)).toBe(0.5)
    expect(niceStep(25)).toBe(25)
    expect(niceStep(12)).toBe(20)
    expect(niceStep(60)).toBe(100)
  })
})

describe('xOf', () => {
  it('計測を等間隔に並べ、両端の点の外側に余白を空ける', () => {
    // 間隔の半分（200 / 6）だけ空ける
    expect(xOf(0, 3, 40, 200)).toBeCloseTo(40 + 200 / 6)
    expect(xOf(1, 3, 40, 200)).toBeCloseTo(140)
    expect(xOf(2, 3, 40, 200)).toBeCloseTo(240 - 200 / 6)
  })

  it('計測が少なく間隔が広くても、余白は上限までにする', () => {
    expect(xOf(0, 2, 40, 1000)).toBe(80)
    expect(xOf(1, 2, 40, 1000)).toBe(1000)
  })

  it('計測が 1 件なら中央に置く', () => {
    expect(xOf(0, 1, 40, 200)).toBe(140)
  })
})

describe('segmentsOf', () => {
  it('計測エラーの点で線を途切れさせる', () => {
    const segments = segmentsOf([
      { runIndex: 0, y: 10 },
      { runIndex: 1, y: 12 },
      { runIndex: 2, y: null },
      { runIndex: 3, y: 11 },
    ])
    expect(segments.map((s) => s.map((p) => p.runIndex))).toEqual([[0, 1], [3]])
  })

  it('その指標を判定していない計測をまたいでつながない', () => {
    const segments = segmentsOf([
      { runIndex: 0, y: 10 },
      { runIndex: 2, y: 12 },
    ])
    expect(segments).toHaveLength(2)
  })
})

describe('stepPathOf', () => {
  it('合格ラインが変わったところで段にする', () => {
    const path = stepPathOf(
      [
        { x: 0, y: 50 },
        { x: 100, y: 40 },
      ],
      0,
      100,
    )
    expect(path).toBe('M0 50 H50 V40 H100')
  })

  it('合格ラインの無い計測で線を切る', () => {
    const path = stepPathOf(
      [
        { x: 0, y: 50 },
        { x: 50, y: null },
        { x: 100, y: 50 },
      ],
      0,
      100,
    )
    expect(path).toBe('M0 50 H25 M75 50 H100')
  })
})
