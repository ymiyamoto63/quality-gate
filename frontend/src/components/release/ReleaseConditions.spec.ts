import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ReleaseConditions from './ReleaseConditions.vue'

describe('ReleaseConditions', () => {
  it('範囲を狭めた設定を、値・説明・環境変数の名前と並べ、見出しでグループに名前を付ける', () => {
    const wrapper = mount(ReleaseConditions, {
      props: {
        conditions: [
          {
            key: 'exclusions',
            label: '計測から外したファイル',
            value: '**/generated/**',
            note: 'このパターンに一致するファイルは計測に含めていません',
            setting: 'QG_EXCLUSIONS',
          },
          {
            key: 'perfScenarios',
            label: '応答時間 p95 のシナリオ',
            value: '指定なし',
            note: 'シナリオを限定せずに判定しています',
            setting: 'QG_PERF_SCENARIOS',
          },
        ],
      },
    })

    expect(wrapper.attributes('role')).toBe('group')
    expect(wrapper.attributes('aria-labelledby')).toBe('conditions-heading')
    expect(wrapper.find('#conditions-heading').text()).toBe('判定の条件')
    expect(wrapper.findAll('dt').map((dt) => dt.text())).toEqual([
      '計測から外したファイル',
      '応答時間 p95 のシナリオ',
    ])
    const first = wrapper.findAll('dd')[0]!
    expect(first.find('.qg-release__condition-value').text()).toBe('**/generated/**')
    expect(first.text()).toContain('このパターンに一致するファイルは計測に含めていません')
    expect(first.find('code').text()).toBe('QG_EXCLUSIONS')
  })

  it('条件を記録する前の計測は、記録が無いことを示す', () => {
    const wrapper = mount(ReleaseConditions, { props: { conditions: null } })

    expect(wrapper.find('#conditions-heading').exists()).toBe(true)
    expect(wrapper.text()).toContain('記録なし')
    expect(wrapper.find('dl').exists()).toBe(false)
  })

  it('範囲を狭める設定が無ければ何も出さない', () => {
    const wrapper = mount(ReleaseConditions, { props: { conditions: [] } })

    expect(wrapper.find('.qg-release__conditions').exists()).toBe(false)
  })
})
