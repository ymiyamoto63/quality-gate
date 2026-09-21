import { defineStore } from 'pinia'
import { ref } from 'vue'

export type Theme = 'light' | 'dark' | 'system'
export interface Toast {
  id: number
  severity: 'success' | 'info' | 'warn' | 'error'
  message: string
}

const THEME_KEY = 'qg.theme'

/** テーマとトーストなど、画面横断の状態。 */
export const useUiStore = defineStore('ui', () => {
  const theme = ref<Theme>(readStoredTheme())
  const toasts = ref<Toast[]>([])
  let nextToastId = 1

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

  function notify(severity: Toast['severity'], message: string) {
    toasts.value.push({ id: nextToastId++, severity, message })
  }

  function dismiss(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  return { theme, toasts, setTheme, applyTheme, notify, dismiss }
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
