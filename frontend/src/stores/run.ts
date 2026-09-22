import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'

export type LoadState = 'idle' | 'loading' | 'ready' | 'error'

/**
 * 応答の型は生成されたスキーマから導く。
 *
 * 手書きで写すと、API が変わってもコンパイルが通ってしまう。項目名を間違えた
 * まま undefined を表示する事故は、型を書き写した瞬間に起こりうる状態になる。
 *
 * springdoc は必須項目を宣言しないため、生成される型は全項目が省略可能になる。
 * 実際には判定済みの Run では埋まっている項目があるが、型の上で緩いままにして
 * 画面側で必ず存在確認を通す。サーバの宣言より強い前提を画面に置かない。
 */
type Schemas = components['schemas']

export type RunMetric = Schemas['RunMetric']
export type RunCategory = Schemas['RunCategory']
export type RunDetail = Schemas['RunDetailResponse']

export const useRunStore = defineStore('run', () => {
  const state = ref<LoadState>('idle')
  const detail = ref<RunDetail | null>(null)
  const errorMessage = ref<string | null>(null)

  async function load(runId: string): Promise<void> {
    state.value = 'loading'
    // 前の Run の内容が残っていると、読み込み中に別の Run の判定が見える
    detail.value = null

    const { data, error } = await api.GET('/api/v1/runs/{runId}', {
      params: { path: { runId } },
    })
    if (error) {
      state.value = 'error'
      errorMessage.value = messageOf(error, 'Run を取得できませんでした')
      return
    }
    detail.value = data
    errorMessage.value = null
    state.value = 'ready'
  }

  return { state, detail, errorMessage, load }
})
