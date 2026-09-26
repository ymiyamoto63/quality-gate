import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { RouterLinkStub } from '@vue/test-utils'
import MetricRow from './MetricRow.vue'
import type { RunMetric } from '@/stores/run'

function metric(overrides: Partial<RunMetric> = {}): RunMetric {
  return {
    metricId: 'M-01',
    name: 'ブランチカバレッジ',
    componentName: 'backend',
    status: 'PASS',
    value: 82.4,
    unit: 'percent',
    threshold: { operator: '>=', value: 75 },
    previousValue: 81.9,
    delta: 0.5,
    deltaImproved: true,
    reason: 'しきい値 75% を満たしています',
    detail: {},
    findingCount: 0,
    variant: null,
    variantLabel: null,
    ...overrides,
  }
}

function render(value: RunMetric) {
  return mount(MetricRow, {
    props: { metric: value, runId: 'run-1' },
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
}

describe('MetricRow', () => {
  it('値・しきい値・前回比・判定理由を並べる', () => {
    const text = render(metric()).text()

    expect(text).toContain('ブランチカバレッジ（backend）')
    expect(text).toContain('82.4%')
    expect(text).toContain('≥ 75%')
    expect(text).toContain('+0.5%')
    expect(text).toContain('しきい値 75% を満たしています')
  })

  it('差分の良し悪しはサーバの判断に従う', () => {
    // 脆弱性件数は減れば改善。符号だけで判断すると向きが逆になる
    const wrapper = render(
      metric({ metricId: 'M-06', unit: 'count', delta: -2, deltaImproved: true }),
    )

    expect(wrapper.find('.qg-metric__arrow').attributes('data-improved')).toBe('true')
    expect(wrapper.text()).toContain('−2 件')
    // 色だけに頼らず、記号と読み上げ用の語も添える
    expect(wrapper.text()).toContain('▲')
    expect(wrapper.text()).toContain('改善')
  })

  it('未計測は 0 ではなく未計測として出す', () => {
    const wrapper = render(
      metric({ status: 'SKIP', value: null, delta: null, deltaImproved: null }),
    )

    expect(wrapper.text()).toContain('—')
    expect(wrapper.find('.qg-metric__delta').exists()).toBe(false)
    expect(wrapper.text()).toContain('未計測')
  })

  it('違反があるときだけ一覧への導線を出す', () => {
    expect(render(metric()).findComponent(RouterLinkStub).exists()).toBe(false)

    const withFindings = render(metric({ findingCount: 2 }))
    const link = withFindings.findComponent(RouterLinkStub)
    expect(withFindings.text()).toContain('違反 2 件を見る')
    // 指標に絞った状態で一覧へ渡す
    expect(link.props('to')).toMatchObject({
      name: 'findings',
      params: { runId: 'run-1' },
      query: { metricId: 'M-01' },
    })
  })

  it('計測条件をコンポーネント名と並べて名前に添える', () => {
    const text = render(
      metric({
        metricId: 'M-02',
        name: 'ミューテーションスコア',
        variant: 'changed',
        variantLabel: '変更範囲',
      }),
    ).text()

    expect(text).toContain('ミューテーションスコア（backend・変更範囲）')
  })

  it('対象外は未計測と出さない', () => {
    const text = render(
      metric({
        metricId: 'M-02',
        componentName: 'frontend',
        status: 'NOT_APPLICABLE',
        value: null,
        unit: null,
        threshold: {},
        delta: null,
        deltaImproved: null,
        reason: 'PIT は JVM 言語専用のため、このコンポーネントは計測の対象外です',
      }),
    ).text()

    expect(text).toContain('対象外')
    expect(text).not.toContain('未計測')
  })

  it('アクセシビリティは自動検査の限界を判定結果によらず添える', () => {
    // 合格でも出す。「重大 0 件」を適合の証明と受け取らせない
    const text = render(
      metric({
        metricId: 'M-09',
        name: 'アクセシビリティ違反',
        componentName: null,
        unit: 'count',
        value: 0,
        threshold: { operator: '<=', value: 0 },
      }),
    ).text()

    expect(text).toContain('必要条件であって十分条件ではありません')
  })

  it('他の指標には注記を出さない', () => {
    expect(render(metric()).find('.qg-metric__note').exists()).toBe(false)
  })
})
