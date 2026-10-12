import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { components } from '@/api/schema'
import ReleaseHistory from './ReleaseHistory.vue'
import { historyItem } from './fixtures'

type HistoryItem = components['schemas']['ReleaseHistoryItem']

async function render(items: HistoryItem[]) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'release', component: { template: '<div />' } },
      { path: '/trends', name: 'trends', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  return mount(ReleaseHistory, { props: { items }, global: { plugins: [router] } })
}

describe('ReleaseHistory', () => {
  it('各行からその計測のリリース判定を開ける（タグが無ければ短い SHA で示す）', async () => {
    const wrapper = await render([
      historyItem(),
      historyItem({ commitSha: 'b'.repeat(40), ref: 'b'.repeat(40), tags: [], verdict: 'FAIL' }),
    ])

    const links = wrapper.findAll('tbody th a')
    expect(links.map((a) => a.text())).toEqual(['v1.0.0', 'bbbbbbb'])
    expect(links[0]!.attributes('href')).toBe('/?ref=v1.0.0')
    expect(wrapper.findAll('tbody .qg-status').map((s) => s.attributes('data-status'))).toEqual([
      'PASS',
      'FAIL',
    ])
  })

  it('印刷物には出さない', async () => {
    const wrapper = await render([historyItem()])

    expect(wrapper.classes()).toContain('qg-no-print')
  })

  it('履歴が無ければ何も出さない', async () => {
    const wrapper = await render([])

    expect(wrapper.find('#history-heading').exists()).toBe(false)
  })
})
