import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusChip from './StatusChip.vue'

describe('StatusChip', () => {
  it('ラベルを文字として出す（色だけに頼らない）', () => {
    const wrapper = mount(StatusChip, { props: { status: 'FAIL' } })

    expect(wrapper.text()).toContain('不合格')
  })

  it('記号とアイコンは装飾として扱い、読み上げから除く', () => {
    const wrapper = mount(StatusChip, { props: { status: 'WARN' } })

    // ラベルが読み上げられるため、記号とアイコンの二重読み上げを避ける
    expect(wrapper.find('.qg-status__mark').attributes('aria-hidden')).toBe('true')
    expect(wrapper.find('i.pi').attributes('aria-hidden')).toBe('true')
  })

  it('ステータスを data 属性に出し、CSS 側で色を切り替える', () => {
    const wrapper = mount(StatusChip, { props: { status: 'NOT_APPLICABLE' } })

    expect(wrapper.attributes('data-status')).toBe('NOT_APPLICABLE')
    expect(wrapper.text()).toContain('対象外')
  })
})
