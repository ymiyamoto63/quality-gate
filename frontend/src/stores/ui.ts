import { defineStore } from 'pinia'
import { ref } from 'vue'

export type Theme = 'light' | 'dark' | 'system'

const THEME_KEY = 'qg.theme'

/** テーマなど、画面横断の状態。 */
export const useUiStore = defineStore('ui', () => {
  const theme = ref<Theme>(readStoredTheme())

  function setTheme(next: Theme) {
    theme.value = next
    applyTheme(next)
    try {
      localStorage.setItem(THEME_KEY, next)
    } catch {
      // プライベートウィンドウなどで書き込めない場合がある。表示は続行する。
    }
  }

  function applyTheme(next: Theme) {
    const root = document.documentElement
    if (next === 'system') {
      root.removeAttribute('data-theme')
    } else {
      root.setAttribute('data-theme', next)
    }
  }

  return { theme, setTheme, applyTheme }
})

function readStoredTheme(): Theme {
  try {
    const stored = localStorage.getItem(THEME_KEY)
    if (stored === 'light' || stored === 'dark' || stored === 'system') return stored
  } catch {
    // 読めない場合は OS 設定に従う
  }
  return 'system'
}
