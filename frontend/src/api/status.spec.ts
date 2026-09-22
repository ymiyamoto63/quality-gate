import { describe, it, expect } from 'vitest'
import { presentationOf, verdictToStatus } from './status'

describe('ステータスの表示定義', () => {
  it('すべてのステータスにラベル・記号・アイコン・色が揃っている', () => {
    const statuses = [
      'PASS',
      'WARN',
      'FAIL',
      'SKIP',
      'REFERENCE',
      'ERROR',
      'NOT_APPLICABLE',
    ] as const

    for (const status of statuses) {
      const p = presentationOf(status)
      expect(p.label, status).toBeTruthy()
      expect(p.mark, status).toBeTruthy()
      expect(p.icon, status).toMatch(/^pi-/)
      expect(p.colorVar, status).toMatch(/^--status-/)
    }
  })

  it('記号は互いに重複しない', () => {
    // 色が使えない状況（モノクロ印刷・強制カラーモード）では記号が唯一の手がかりになる
    const statuses = [
      'PASS',
      'WARN',
      'FAIL',
      'SKIP',
      'REFERENCE',
      'ERROR',
      'NOT_APPLICABLE',
    ] as const
    const marks = statuses.map((s) => presentationOf(s).mark)

    expect(new Set(marks).size).toBe(statuses.length)
  })

  it('未計測・参考値・対象外は中立色で、良し悪しを表さない', () => {
    expect(presentationOf('SKIP').colorVar).toBe('--status-neutral')
    expect(presentationOf('REFERENCE').colorVar).toBe('--status-neutral')
    expect(presentationOf('NOT_APPLICABLE').colorVar).toBe('--status-neutral')
  })

  it('対象外は未計測と別の語で示す', () => {
    // ツールの制約で測りようがないものを、測り忘れと混同させない
    expect(presentationOf('NOT_APPLICABLE').label).toBe('対象外')
    expect(presentationOf('NOT_APPLICABLE').label).not.toBe(presentationOf('SKIP').label)
  })

  it('不合格と計測エラーは別の色で表す', () => {
    // 品質の問題（開発者が直す）とシステムの問題（基盤管理者が直す）を区別する
    expect(presentationOf('FAIL').colorVar).not.toBe(presentationOf('ERROR').colorVar)
  })

  it('Run の判定を指標と同じ表示語彙に写せる', () => {
    expect(verdictToStatus('PASS')).toBe('PASS')
    expect(verdictToStatus('PASS_WITH_WARNINGS')).toBe('WARN')
    expect(verdictToStatus('FAIL')).toBe('FAIL')
    expect(verdictToStatus(null)).toBe('SKIP')
    expect(verdictToStatus(undefined)).toBe('SKIP')
  })
})
