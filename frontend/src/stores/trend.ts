import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'

export type LoadState = 'idle' | 'loading' | 'ready' | 'error'

type Schemas = components['schemas']

/** 応答の型は生成されたスキーマから導く（理由は run ストアの注記と同じ）。 */
export type Trend = Schemas['TrendResponse']
export type TrendSeries = Schemas['TrendSeries']

/** 期間の選択肢。最大 2 年はサーバ側の上限と揃える（FR-08-1）。 */
export const RANGES = [
  { days: 30, label: '直近 30 日' },
  { days: 90, label: '直近 90 日' },
  { days: 365, label: '直近 1 年' },
] as const

export const useTrendStore = defineStore('trend', () => {
  const state = ref<LoadState>('idle')
  const trend = ref<Trend | null>(null)
  const errorMessage = ref<string | null>(null)
  const metricId = ref('M-01')
  const days = ref<number>(RANGES[0].days)
  const branch = ref<string | null>(null)

  async function load(repositoryId: string): Promise<void> {
    state.value = 'loading'
    // 前の指標の線が残っていると、読み込み中に別の指標の推移が見える
    trend.value = null

    const to = new Date()
    const from = new Date(to.getTime() - days.value * 24 * 60 * 60 * 1000)

    const { data, error } = await api.GET('/api/v1/repositories/{repositoryId}/trends', {
      params: {
        path: { repositoryId },
        query: {
          metricId: metricId.value,
          from: from.toISOString(),
          to: to.toISOString(),
          ...(branch.value === null ? {} : { branch: branch.value }),
        },
      },
    })
    if (error) {
      state.value = 'error'
      errorMessage.value = messageOf(error, 'トレンドを取得できませんでした')
      return
    }
    trend.value = data
    errorMessage.value = null
    state.value = 'ready'
  }

  return { state, trend, errorMessage, metricId, days, branch, load }
})
