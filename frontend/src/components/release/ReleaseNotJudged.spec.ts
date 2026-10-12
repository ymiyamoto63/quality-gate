import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ReleaseNotJudged from './ReleaseNotJudged.vue'

describe('ReleaseNotJudged', () => {
  it('対象外の指標を件数・理由と並べ、見出しでグループに名前を付ける', () => {
    const wrapper = mount(ReleaseNotJudged, {
      props: {
        items: [
          {
            metricId: 'M-02',
            name: 'ミューテーションスコア',
            componentName: 'frontend',
            reason: 'r1',
          },
          { metricId: 'M-03', name: '応答時間 p95', componentName: null, reason: 'r2' },
        ],
      },
    })

    expect(wrapper.attributes('role')).toBe('group')
    expect(wrapper.attributes('aria-labelledby')).toBe('scope-heading')
    expect(wrapper.find('#scope-heading').text()).toBe('判定の対象外（2 件）')
    expect(wrapper.findAll('li').map((li) => li.text())).toEqual([
      'ミューテーションスコア（frontend）: r1',
      '応答時間 p95: r2',
    ])
  })

  it('対象外が無ければ何も出さない', () => {
    const wrapper = mount(ReleaseNotJudged, { props: { items: [] } })

    expect(wrapper.find('.qg-release__scope').exists()).toBe(false)
  })
})
