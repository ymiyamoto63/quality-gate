import { describe, it, expect } from 'vitest'
import { CATEGORY_ORDER, categoryDetail, summarizeByCategory } from './category'

describe('カテゴリごとの要約', () => {
  it('6 カテゴリを決まった順で返し、項目の無いカテゴリは未計測として残す', () => {
    const summaries = summarizeByCategory([{ category: 'セキュリティ', status: 'PASS' }])

    expect(summaries.map((s) => s.category)).toEqual([...CATEGORY_ORDER])
    expect(summaries.find((s) => s.category === 'セキュリティ')?.status).toBe('PASS')
    expect(summaries.find((s) => s.category === '機能テスト')?.status).toBe('NOT_MEASURED')
  })

  it('1 項目でも不合格があればカテゴリは不合格で、計測エラーより優先する', () => {
    const [functional] = summarizeByCategory([
      { category: '機能テスト', status: 'PASS' },
      { category: '機能テスト', status: 'ERROR' },
      { category: '機能テスト', status: 'FAIL' },
    ])

    expect(functional).toMatchObject({ status: 'FAIL', judged: 3, failed: 1, errored: 1 })
    expect(categoryDetail(functional!)).toBe('3 項目中 1 項目が不合格、1 項目が計測エラー')
  })

  it('不合格が無く計測エラーがあれば計測エラー', () => {
    const [functional] = summarizeByCategory([
      { category: '機能テスト', status: 'PASS' },
      { category: '機能テスト', status: 'ERROR' },
    ])

    expect(functional?.status).toBe('ERROR')
  })

  it('すべて合格なら合格', () => {
    const [functional] = summarizeByCategory([
      { category: '機能テスト', status: 'PASS' },
      { category: '機能テスト', status: 'PASS' },
    ])

    expect(functional?.status).toBe('PASS')
    expect(categoryDetail(functional!)).toBe('2 項目すべて合格')
  })

  it('知らないカテゴリは消さずに末尾へ並べる', () => {
    const summaries = summarizeByCategory([{ category: '新しい分野', status: 'FAIL' }])

    expect(summaries.at(-1)).toMatchObject({ category: '新しい分野', status: 'FAIL' })
  })
})
