import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { components } from '@/api/schema'
import ReleaseView from './ReleaseView.vue'

type Report = components['schemas']['ReleaseReportResponse']
type Row = Report['metrics'][number]

const get = vi.fn()
vi.mock('@/api/client', () => ({
  api: { GET: (...args: unknown[]) => get(...args) },
  messageOf: (_: unknown, fallback: string) => fallback,
}))

function row(overrides: Partial<Row>): Row {
  return {
    metricId: 'M-01',
    name: 'ブランチカバレッジ',
    componentName: 'backend',
    variantLabel: null,
    status: 'PASS',
    value: 85,
    unit: 'percent',
    threshold: '≥ 70%',
    thresholdRelaxed: false,
    defaultThreshold: null,
    excludedFileCount: 0,
    reason: null,
    findings: [],
    findingCount: 0,
    ...overrides,
  }
}

function report(overrides: Partial<Report>): Report {
  return {
    repositoryFullName: 'example/app',
    commitSha: '1'.repeat(40),
    commitUrl: null,
    decision: 'RELEASABLE',
    decisionReason: '判定した 1 件の指標が、すべて合格ラインを満たしています。',
    run: { measuredAt: '2026-09-20T03:00:00Z', tags: [], ciRunUrl: null, baseCommitSha: null },
    counts: { judged: 1, passed: 1, failed: 0 },
    metrics: [row({})],
    notJudged: [],
    guides: [],
    ...overrides,
  }
}

async function render(data: Report) {
  get.mockImplementation((path: string) =>
    Promise.resolve(path === '/api/v1/release' ? { data } : { data: { items: [] } }),
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'release', component: ReleaseView },
      { path: '/trends', name: 'trends', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  const wrapper = mount(ReleaseView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('ReleaseView', () => {
  beforeEach(() => get.mockReset())

  it('判定の対象外にした指標を、結論のすぐ下に理由と並べる', async () => {
    const wrapper = await render(
      report({
        notJudged: [
          {
            metricId: 'M-02',
            name: 'ミューテーションスコア',
            componentName: 'frontend',
            reason: 'PIT は JVM 言語専用のため、このコンポーネントは計測の対象外です',
          },
          {
            metricId: 'M-03',
            name: '応答時間 p95',
            componentName: null,
            reason: '設定（QG_DISABLED_METRICS）で判定から外しています',
          },
        ],
      }),
    )

    const scope = wrapper.find('.qg-release__decision .qg-release__scope')
    expect(scope.exists()).toBe(true)
    expect(scope.find('h3').text()).toContain('判定の対象外（2 件）')
    expect(scope.text()).toContain('ミューテーションスコア（frontend）')
    expect(scope.text()).toContain(
      '応答時間 p95: 設定（QG_DISABLED_METRICS）で判定から外しています',
    )
  })

  it('判定の対象外が無ければ何も出さない', async () => {
    const wrapper = await render(report({}))

    expect(wrapper.find('.qg-release__scope').exists()).toBe(false)
    expect(wrapper.find('.qg-release__loosened').exists()).toBe(false)
  })

  it('既定より緩めた合格ラインと除外したファイルを行に示す', async () => {
    const wrapper = await render(
      report({
        metrics: [row({ thresholdRelaxed: true, defaultThreshold: '≥ 75%', excludedFileCount: 3 })],
      }),
    )

    const cell = wrapper.find('td[data-label="合格ライン"]')
    expect(cell.text()).toContain('≥ 70%')
    expect(cell.text()).toContain('既定より緩い（既定 ≥ 75%）')
    expect(cell.text()).toContain('3 ファイルを計測から除外（QG_EXCLUSIONS）')
    // 結論の近くでも、緩めた指標があることを知らせる
    expect(wrapper.find('.qg-release__scope-note').text()).toContain(
      '既定より緩めて判定した指標が 1 件',
    )
  })
})
