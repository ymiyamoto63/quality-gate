import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useUiStore } from './ui'

describe('UI ストア', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.documentElement.removeAttribute('data-theme')
    localStorage.clear()
  })

  it('ダークを選ぶと data-theme が設定される', () => {
    const ui = useUiStore()

    ui.setTheme('dark')

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem('qg.theme')).toBe('dark')
  })

  it('OS 追従を選ぶと data-theme を外す', () => {
    const ui = useUiStore()
    ui.setTheme('dark')

    ui.setTheme('system')

    // 属性を外すことで、tokens.css の prefers-color-scheme が効くようになる
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('localStorage が使えなくても表示は続行する', () => {
    const ui = useUiStore()
    const original = Storage.prototype.setItem
    Storage.prototype.setItem = () => {
      throw new Error('QuotaExceededError')
    }

    try {
      expect(() => ui.setTheme('light')).not.toThrow()
      expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    } finally {
      Storage.prototype.setItem = original
    }
  })

  it('トーストを追加・削除できる', () => {
    const ui = useUiStore()

    ui.notify('error', '取得に失敗しました')
    expect(ui.toasts).toHaveLength(1)

    ui.dismiss(ui.toasts[0]!.id)
    expect(ui.toasts).toHaveLength(0)
  })
})
