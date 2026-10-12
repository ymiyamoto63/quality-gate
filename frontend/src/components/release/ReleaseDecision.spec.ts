import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ReleaseDecision from './ReleaseDecision.vue'
import { metricRow, releaseReport } from './fixtures'

describe('ReleaseDecision', () => {
  afterEach(() => vi.restoreAllMocks())

  it('結論を見出しの文言で示し、チップの記号は読み上げから除く', () => {
    const wrapper = mount(ReleaseDecision, {
      props: { report: releaseReport({ decision: 'NOT_RELEASABLE' }) },
    })

    expect(wrapper.attributes('data-decision')).toBe('NOT_RELEASABLE')
    expect(wrapper.attributes('aria-labelledby')).toBe('decision-heading')
    expect(wrapper.find('#decision-heading').text()).toMatch(/リリース不可$/)
    expect(wrapper.find('.qg-release__mark').attributes('aria-hidden')).toBe('true')
  })

  it('不合格があれば、合格と不合格の件数を並べる', () => {
    const wrapper = mount(ReleaseDecision, {
      props: { report: releaseReport({ counts: { judged: 3, passed: 1, failed: 2 } }) },
    })

    expect(wrapper.find('.qg-release__score').text()).toBe(
      '品質基準 3 項目のうち 1 項目が合格、2 項目が不合格',
    )
  })

  it('判定した指標が無ければ件数を出さない', () => {
    const wrapper = mount(ReleaseDecision, {
      props: {
        report: releaseReport({
          decision: 'NOT_MEASURED',
          counts: { judged: 0, passed: 0, failed: 0 },
          commitSha: null,
        }),
      },
    })

    expect(wrapper.find('#decision-heading').text()).toMatch(/未計測$/)
    expect(wrapper.find('.qg-release__score').exists()).toBe(false)
    expect(wrapper.find('.qg-release__facts').text()).not.toContain('コミット')
  })

  it('既定より緩めて判定した指標の数を知らせる', () => {
    const wrapper = mount(ReleaseDecision, {
      props: {
        report: releaseReport({
          metrics: [
            metricRow({ thresholdRelaxed: true }),
            metricRow({ metricId: 'M-02', thresholdRelaxed: true }),
            metricRow({ metricId: 'M-03' }),
          ],
        }),
      },
    })

    expect(wrapper.find('.qg-release__scope-note').text()).toContain(
      '既定より緩めて判定した指標が 2 件',
    )
  })

  it('判定の条件を、判定の対象外と並べて結論の下に示す', () => {
    const wrapper = mount(ReleaseDecision, {
      props: {
        report: releaseReport({
          notJudged: [
            { metricId: 'M-02', name: 'ミューテーションスコア', componentName: null, reason: 'r' },
          ],
          conditions: [
            {
              key: 'exclusions',
              label: '計測から外したファイル',
              value: '**/generated/**',
              note: 'n',
              setting: 'QG_EXCLUSIONS',
            },
          ],
        }),
      },
    })

    expect(wrapper.find('#scope-heading').exists()).toBe(true)
    expect(wrapper.find('#conditions-heading').exists()).toBe(true)
    expect(wrapper.find('.qg-release__conditions dt').text()).toBe('計測から外したファイル')
  })

  it('条件を記録する前の計測では記録なしと示し、未計測では出さない', () => {
    const old = mount(ReleaseDecision, { props: { report: releaseReport({ conditions: null }) } })
    expect(old.find('.qg-release__conditions').text()).toContain('記録なし')

    const notMeasured = mount(ReleaseDecision, {
      props: {
        report: releaseReport({
          decision: 'NOT_MEASURED',
          // API は未計測で run を null にする（生成した型は $ref の null を表せない）
          run: null as never,
          conditions: null,
          counts: { judged: 0, passed: 0, failed: 0 },
          metrics: [],
        }),
      },
    })
    expect(notMeasured.find('.qg-release__conditions').exists()).toBe(false)
  })

  it('判定に使った計測をリンク付きで示す', () => {
    const wrapper = mount(ReleaseDecision, {
      props: {
        report: releaseReport({
          commitUrl: 'https://github.com/example/app/commit/1111111',
          run: {
            measuredAt: '2026-09-20T03:00:00Z',
            tags: ['v1.2.0', 'latest'],
            ciRunUrl: 'https://github.com/example/app/actions/runs/1',
            baseCommitSha: '2'.repeat(40),
          },
        }),
      },
    })

    const facts = wrapper.find('.qg-release__facts')
    expect(facts.text()).toContain('v1.2.0, latest')
    const links = facts.findAll('a')
    expect(links.map((a) => a.attributes('href'))).toEqual([
      'https://github.com/example/app/commit/1111111',
      'https://github.com/example/app/actions/runs/1',
    ])
    expect(links.every((a) => a.attributes('rel') === 'noopener')).toBe(true)
    expect(facts.text()).toContain('比較元')
  })

  it('印刷の操作は印刷物に出さない', async () => {
    const print = vi.spyOn(window, 'print').mockImplementation(() => {})
    const wrapper = mount(ReleaseDecision, { props: { report: releaseReport() } })

    const actions = wrapper.find('.qg-release__actions')
    expect(actions.classes()).toContain('qg-no-print')
    await actions.find('button').trigger('click')
    expect(print).toHaveBeenCalledOnce()
  })
})
