import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ReleaseGuides from './ReleaseGuides.vue'
import { guide } from './fixtures'

describe('ReleaseGuides', () => {
  it('指標ごとに説明と根拠の種類を並べる', () => {
    const wrapper = mount(ReleaseGuides, {
      props: { guides: [guide(), guide({ metricId: 'M-02', name: '脆弱性', basis: 'EXTERNAL' })] },
    })

    const articles = wrapper.findAll('article')
    expect(articles).toHaveLength(2)
    const basis = articles[0]!.find('.qg-release__basis')
    expect(basis.text()).toBe('業界の目安')
    expect(basis.attributes('data-basis')).toBe('INDUSTRY')
    expect(articles[0]!.find('dl').text()).toContain('JaCoCo')
  })

  it('説明が無ければ何も出さない', () => {
    const wrapper = mount(ReleaseGuides, { props: { guides: [] } })

    expect(wrapper.find('#guides-heading').exists()).toBe(false)
  })
})
