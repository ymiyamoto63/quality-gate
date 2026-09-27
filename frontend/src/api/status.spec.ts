import { describe, it, expect } from 'vitest'
import { presentationOf, verdictToStatus } from './status'

const STATUSES = ['PASS', 'FAIL', 'ERROR', 'NOT_MEASURED', 'NOT_APPLICABLE'] as const

describe('ステータスの表示定義', () => {
  it('すべてのステータスにラベル・記号・アイコン・色が揃っている', () => {
    for (const status of STATUSES) {
      const p = presentationOf(status)
      expect(p.label, status).toBeTruthy()
      expect(p.mark, status).toBeTruthy()
      expect(p.icon, status).toMatch(/^pi-/)
      expect(p.colorVar, status).toMatch(/^--status-/)
    }
  })

  it('記号は互いに重複しない', () => {
    // 色が使えない状況（モノクロ印刷・強制カラーモード）では記号が唯一の手がかりになる
    const marks = STATUSES.map((s) => presentationOf(s).mark)

    expect(new Set(marks).size).toBe(STATUSES.length)
  })

  it('未計測・対象外は中立色で、良し悪しを表さない', () => {
    expect(presentationOf('NOT_MEASURED').colorVar).toBe('--status-neutral')
    expect(presentationOf('NOT_APPLICABLE').colorVar).toBe('--status-neutral')
  })

  it('不合格と計測エラーは別の色で表す', () => {
    // 品質の問題（開発者が直す）と計測の問題（収集ランナーを直す）を区別する
    expect(presentationOf('FAIL').colorVar).not.toBe(presentationOf('ERROR').colorVar)
  })

  it('計測全体の判定を指標と同じ表示語彙に写せる', () => {
    expect(verdictToStatus('PASS')).toBe('PASS')
    expect(verdictToStatus('FAIL')).toBe('FAIL')
    expect(verdictToStatus(null)).toBe('NOT_MEASURED')
    expect(verdictToStatus(undefined)).toBe('NOT_MEASURED')
  })
})
