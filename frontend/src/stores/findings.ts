import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'

export type LoadState = 'idle' | 'loading' | 'ready' | 'error'
type Schemas = components['schemas']

/** 応答の型は生成されたスキーマから導く（理由は run ストアの注記と同じ）。 */
export type FindingItem = Schemas['FindingItem']
export type FindingState = NonNullable<FindingItem['state']>
export type Severity = NonNullable<FindingItem['severity']>

export interface FindingFilter {
  metricId: string[]
  state: FindingState[]
  severity: Severity[]
}

/** 既定は新規 + 継続 + 初回。解消済みは明示的に選んだときだけ出す。 */
export function defaultFilter(): FindingFilter {
  return { metricId: [], state: ['NEW', 'CONTINUING', 'INITIAL'], severity: [] }
}

export const useFindingsStore = defineStore('findings', () => {
  const state = ref<LoadState>('idle')
  const items = ref<FindingItem[]>([])
  const totalCount = ref(0)
  const nextCursor = ref<string | null>(null)
  const errorMessage = ref<string | null>(null)
  const filter = ref<FindingFilter>(defaultFilter())

  async function load(runId: string): Promise<void> {
    state.value = 'loading'
    items.value = []
    nextCursor.value = null
    await fetchPage(runId, null)
  }

  /**
   * 次ページを現在の一覧に足す。置き換えないのは、違反一覧が
   * 「上から順に見ていく」使われ方をするため。ページ送りで前が消えると読み直しになる。
   */
  async function loadMore(runId: string): Promise<void> {
    if (nextCursor.value === null) return
    await fetchPage(runId, nextCursor.value)
  }

  function setFilter(runId: string, next: FindingFilter): Promise<void> {
    filter.value = next
    return load(runId)
  }

  async function fetchPage(runId: string, cursor: string | null): Promise<void> {
    const { data, error } = await api.GET('/api/v1/runs/{runId}/findings', {
      params: {
        path: { runId },
        query: {
          metricId: filter.value.metricId,
          state: filter.value.state,
          severity: filter.value.severity,
          ...(cursor === null ? {} : { cursor }),
        },
      },
    })
    if (error) {
      state.value = 'error'
      errorMessage.value = messageOf(error, '違反一覧を取得できませんでした')
      return
    }
    items.value = items.value.concat(data.items ?? [])
    nextCursor.value = data.nextCursor ?? null
    totalCount.value = data.totalCount ?? 0
    errorMessage.value = null
    state.value = 'ready'
  }

  return {
    state,
    items,
    totalCount,
    nextCursor,
    errorMessage,
    filter,
    load,
    loadMore,
    setFilter,
  }
})
