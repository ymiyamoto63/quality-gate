import { describe, it, expect } from 'vitest'
import { formatValue, formatDelta, formatThreshold, shortSha, NOT_MEASURED } from './format'

describe('formatValue', () => {
  it('未計測と 0 を区別する', () => {
    // 未計測を 0 と表示すると「計測して 0 だった」と見分けがつかなくなる
    expect(formatValue(null, 'count')).toBe(NOT_MEASURED)
    expect(formatValue(0, 'count')).toBe('0 件')
  })

  it('単位を記号に直す', () => {
    expect(formatValue(82.4, 'percent')).toBe('82.4%')
    expect(formatValue(412.5, 'ms')).toBe('412.5ms')
  })

  it('余計な 0 を落とす', () => {
    expect(formatValue('82.0000', 'percent')).toBe('82%')
  })

  it('未知の単位はそのまま添える', () => {
    expect(formatValue(3, 'req/s')).toBe('3 req/s')
  })
})

describe('formatDelta', () => {
  it('符号を必ず付ける', () => {
    expect(formatDelta(0.5, 'percent')).toBe('+0.5%')
    expect(formatDelta(-2, 'count')).toBe('−2 件')
  })

  it('差が無ければ表示しない', () => {
    // 「±0」を出すと変化があったように見える
    expect(formatDelta(0, 'percent')).toBeNull()
    expect(formatDelta(null, 'percent')).toBeNull()
  })
})

describe('formatThreshold', () => {
  it('演算子を記号にする', () => {
    expect(formatThreshold({ operator: '>=', value: 75 }, 'percent')).toBe('≥ 75%')
    expect(formatThreshold({ operator: '<=', value: 0 }, 'count')).toBe('≤ 0 件')
  })

  it('しきい値が無ければ表示しない', () => {
    expect(formatThreshold(null, 'percent')).toBeNull()
    expect(formatThreshold({}, 'percent')).toBeNull()
  })
})

describe('shortSha', () => {
  it('先頭 7 桁にする', () => {
    expect(shortSha('a1b2c3d4e5f6')).toBe('a1b2c3d')
    expect(shortSha(null)).toBe(NOT_MEASURED)
  })
})
