/**
 * 判定ステータスの表示定義（docs/initial/08-screen-design.md 3.1）。
 *
 * ラベル・記号・アイコン・色の 4 つを常に同時に使う。
 * 色覚特性、モノクロ印刷、強制カラーモードのいずれでも意味が失われないようにするため。
 */
export type MeasurementStatus = 'PASS' | 'WARN' | 'FAIL' | 'SKIP' | 'ERROR' | 'NOT_APPLICABLE'
export type Verdict = 'PASS' | 'PASS_WITH_WARNINGS' | 'FAIL'

export interface StatusPresentation {
  label: string
  mark: string
  icon: string
  colorVar: string
}

const PRESENTATIONS: Record<MeasurementStatus, StatusPresentation> = {
  PASS: { label: '合格', mark: '●', icon: 'pi-check-circle', colorVar: '--status-pass' },
  WARN: { label: '注意', mark: '▲', icon: 'pi-exclamation-triangle', colorVar: '--status-warn' },
  FAIL: { label: '不合格', mark: '■', icon: 'pi-times-circle', colorVar: '--status-fail' },
  ERROR: { label: '計測エラー', mark: '◆', icon: 'pi-question-circle', colorVar: '--status-error' },
  // SKIP に status 色を割り当てないのは、「良い / 悪い」を
  // 表さないため。未計測を黄色にすると「注意すべき悪い状態」に見えてしまう。
  SKIP: { label: '未計測', mark: '○', icon: 'pi-minus-circle', colorVar: '--status-neutral' },
  // 対象外は「ツールの制約で測りようがない」（M-02 の frontend など）。未計測と同じ見た目に
  // すると、測り忘れの積み残しと誤読される（docs/initial/02-metrics-spec.md M-02）
  NOT_APPLICABLE: { label: '対象外', mark: '—', icon: 'pi-ban', colorVar: '--status-neutral' },
}

export function presentationOf(status: MeasurementStatus): StatusPresentation {
  return PRESENTATIONS[status]
}

/** Run 全体の判定を、指標と同じ表示語彙に写す。 */
export function verdictToStatus(verdict: Verdict | null | undefined): MeasurementStatus {
  if (verdict === 'FAIL') return 'FAIL'
  if (verdict === 'PASS_WITH_WARNINGS') return 'WARN'
  if (verdict === 'PASS') return 'PASS'
  return 'SKIP'
}
