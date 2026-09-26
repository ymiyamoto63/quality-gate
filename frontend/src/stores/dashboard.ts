import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, messageOf } from '@/api/client'
import { useUiStore } from './ui'

export type LoadState = 'idle' | 'loading' | 'ready' | 'error'

export const useDashboardStore = defineStore('dashboard', () => {
  const state = ref<LoadState>('idle')
  const repositories = ref<unknown[]>([])
  const errorMessage = ref<string | null>(null)

  let pollTimer: ReturnType<typeof setInterval> | null = null

  /**
   * キャッシュは持たない。古い判定結果を表示する事故のほうが、
   * 再取得のコストより重いため。表示速度は読み取りモデル側で担保する。
   */
  async function load(): Promise<void> {
    state.value = 'loading'
    const { data, error } = await api.GET('/api/v1/dashboard')
    if (error) {
      state.value = 'error'
      errorMessage.value = messageOf(error, 'ダッシュボードを取得できませんでした')
      useUiStore().notify('error', errorMessage.value)
      return
    }
    const payload = (data ?? {}) as { repositories?: unknown[] }
    repositories.value = payload.repositories ?? []
    errorMessage.value = null
    state.value = 'ready'
  }

  /** タブが非表示の間はポーリングしない（無駄な通信とサーバ負荷を避ける）。 */
  function startPolling(intervalMs = 60_000): void {
    stopPolling()
    pollTimer = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void load()
      }
    }, intervalMs)
  }

  function stopPolling(): void {
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  return { state, repositories, errorMessage, load, startPolling, stopPolling }
})
