/**
 * 判定ステータスの表示定義（docs/architecture.md 7.3）。
 *
 * ラベル・記号・アイコン・色の 4 つを常に同時に使う。
 * 色覚特性、モノクロ印刷、強制カラーモードのいずれでも意味が失われないようにするため。
 *
 * 合否は 2 値（合格 / 不合格）。計測エラーは不合格として扱うが、品質の問題と計測の問題を
 * 見分けられるよう、別の語と色で示す。
 */
export type MeasurementStatus = 'PASS' | 'FAIL' | 'ERROR' | 'NOT_APPLICABLE'
export type Verdict = 'PASS' | 'FAIL'
/** 画面に出す状態。計測が無い（未計測）ことも表す。 */
export type DisplayStatus = MeasurementStatus | 'NOT_MEASURED'

export interface StatusPresentation {
  label: string
  mark: string
  icon: string
  colorVar: string
}

const PRESENTATIONS: Record<DisplayStatus, StatusPresentation> = {
  PASS: { label: '合格', mark: '●', icon: 'pi-check-circle', colorVar: '--status-pass' },
  FAIL: { label: '不合格', mark: '■', icon: 'pi-times-circle', colorVar: '--status-fail' },
  ERROR: { label: '計測エラー', mark: '◆', icon: 'pi-question-circle', colorVar: '--status-error' },
  // 未計測・対象外に status 色を割り当てないのは、「良い / 悪い」を表さないため
  NOT_MEASURED: {
    label: '未計測',
    mark: '○',
    icon: 'pi-minus-circle',
    colorVar: '--status-neutral',
  },
  NOT_APPLICABLE: { label: '対象外', mark: '—', icon: 'pi-ban', colorVar: '--status-neutral' },
}

export function presentationOf(status: DisplayStatus): StatusPresentation {
  return PRESENTATIONS[status]
}

/** 計測全体の判定を、指標と同じ表示語彙に写す。 */
export function verdictToStatus(verdict: Verdict | null | undefined): DisplayStatus {
  if (verdict === 'FAIL') return 'FAIL'
  if (verdict === 'PASS') return 'PASS'
  return 'NOT_MEASURED'
}
