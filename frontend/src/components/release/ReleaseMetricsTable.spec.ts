import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ReleaseMetricsTable from './ReleaseMetricsTable.vue'
import { guide, metricRow } from './fixtures'

describe('ReleaseMetricsTable', () => {
  it('既定より緩めた合格ラインと除外したファイルを行に示す', () => {
    const wrapper = mount(ReleaseMetricsTable, {
      props: {
        metrics: [
          metricRow({ thresholdRelaxed: true, defaultThreshold: '≥ 75%', excludedFileCount: 3 }),
        ],
        guides: [],
      },
    })

    const cell = wrapper.find('td[data-label="合格ライン"]')
    expect(cell.text()).toContain('≥ 70%')
    expect(cell.text()).toContain('既定より緩い（既定 ≥ 75%）')
    expect(cell.text()).toContain('3 ファイルを計測から除外（QG_EXCLUSIONS）')
  })

  it('「何を見るか」を指標の説明から引き、コンポーネントと計測条件を添える', () => {
    const wrapper = mount(ReleaseMetricsTable, {
      props: {
        metrics: [metricRow({ variantLabel: 'Java 21' })],
        guides: [guide({ summary: '分岐のうちテストで通った割合' })],
      },
    })

    expect(wrapper.find('th[scope="row"]').text()).toBe('ブランチカバレッジ（backend・Java 21）')
    expect(wrapper.find('td[data-label="何を見るか"]').text()).toBe('分岐のうちテストで通った割合')
  })

  it('不合格の行に理由と主な違反を添え、出しきれない件数を示す', () => {
    const wrapper = mount(ReleaseMetricsTable, {
      props: {
        metrics: [
          metricRow({
            status: 'FAIL',
            reason: '合格ラインを下回っています',
            findingCount: 3,
            findings: [
              { title: '違反 A', location: 'A.java:1', url: 'https://example.com/a' },
              { title: '違反 B', location: null, url: null },
            ],
          }),
        ],
        guides: [],
      },
    })

    const row = wrapper.find('tbody tr')
    expect(row.attributes('data-status')).toBe('FAIL')
    expect(row.find('.qg-release__why').text()).toBe('合格ラインを下回っています')
    const findings = row.find('.qg-release__findings')
    expect(findings.find('summary').text()).toBe('主な違反（3 件）')
    expect(findings.find('a').attributes('href')).toBe('https://example.com/a')
    expect(findings.findAll('li')).toHaveLength(2)
    expect(findings.find('p.qg-muted').text()).toContain('ほか 1 件')
  })

  it('合格の行には理由を出さない', () => {
    const wrapper = mount(ReleaseMetricsTable, {
      props: { metrics: [metricRow({ reason: '参考情報' })], guides: [] },
    })

    expect(wrapper.find('.qg-release__why').exists()).toBe(false)
  })

  it('行が無ければ何も出さない', () => {
    const wrapper = mount(ReleaseMetricsTable, { props: { metrics: [], guides: [] } })

    expect(wrapper.find('table').exists()).toBe(false)
  })
})
