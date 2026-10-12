import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { components } from '@/api/schema'
import { guide, historyItem, metricRow, releaseReport } from '@/components/release/fixtures'
import ReleaseView from './ReleaseView.vue'

type Report = components['schemas']['ReleaseReportResponse']
type HistoryItem = components['schemas']['ReleaseHistoryItem']

const get = vi.fn()
vi.mock('@/api/client', () => ({
  api: { GET: (...args: unknown[]) => get(...args) },
  messageOf: (_: unknown, fallback: string) => fallback,
}))

async function render(data: Report | null, history: HistoryItem[] = []) {
  get.mockImplementation((path: string) =>
    Promise.resolve(
      path === '/api/v1/release'
        ? data
          ? { data }
          : { error: { message: 'x' } }
        : { data: { items: history } },
    ),
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
      releaseReport({
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
    const wrapper = await render(releaseReport())

    expect(wrapper.find('.qg-release__scope').exists()).toBe(false)
    expect(wrapper.find('.qg-release__loosened').exists()).toBe(false)
  })

  it('結論、指標ごとの合否、指標の説明、判定の履歴の順に並べる', async () => {
    const wrapper = await render(
      releaseReport({
        metrics: [metricRow({ thresholdRelaxed: true, defaultThreshold: '≥ 75%' })],
        guides: [guide({ summary: '分岐のうちテストで通った割合' })],
      }),
      [historyItem()],
    )

    const headings = wrapper.findAll('h2').map((h) => h.attributes('id'))
    expect(headings).toEqual([
      'decision-heading',
      'metrics-heading',
      'guides-heading',
      'history-heading',
    ])
    expect(wrapper.find('h1').text()).toContain('example/app')
    // 結論の近くの注記と表の行が、同じ判定結果から出ている
    expect(wrapper.find('.qg-release__scope-note').text()).toContain('1 件')
    expect(wrapper.find('td[data-label="何を見るか"]').text()).toBe('分岐のうちテストで通った割合')
  })

  it('取得に失敗したら理由を知らせ、結論は出さない', async () => {
    const wrapper = await render(null)

    expect(wrapper.find('[role="alert"]').text()).toBe('リリース判定を取得できませんでした')
    expect(wrapper.find('.qg-release__decision').exists()).toBe(false)
  })
})
