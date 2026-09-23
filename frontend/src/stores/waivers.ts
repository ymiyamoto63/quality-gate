import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'

type Schemas = components['schemas']
export type WaiverItem = Schemas['WaiverItem']
export type WaiverStatus = WaiverItem['status']
export type CreateWaiverRequest = Schemas['CreateWaiverRequest']

/** 免除の一覧・登録・失効（docs/08-screen-design.md 7 章 useWaiverStore）。 */
export const useWaiverStore = defineStore('waivers', () => {
  const state = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const items = ref<WaiverItem[]>([])
  const activeCount = ref(0)
  const expiringSoonCount = ref(0)
  const errorMessage = ref<string | null>(null)

  async function load(repositoryId: string | null, status: WaiverStatus | null): Promise<void> {
    state.value = 'loading'
    const { data, error } = await api.GET('/api/v1/waivers', {
      params: {
        query: {
          ...(repositoryId ? { repositoryId } : {}),
          ...(status ? { status } : {}),
        },
      },
    })
    if (error) {
      state.value = 'error'
      errorMessage.value = messageOf(error, '免除を取得できませんでした')
      return
    }
    items.value = data.items
    activeCount.value = data.activeCount
    expiringSoonCount.value = data.expiringSoonCount
    state.value = 'ready'
  }

  /** @returns エラーの内容（成功なら null）。入力欄に結びつけるため呼び出し側へ返す */
  async function create(
    request: CreateWaiverRequest,
  ): Promise<{ message: string; fields: Record<string, string> } | null> {
    const { error } = await api.POST('/api/v1/waivers', { body: request })
    if (!error) return null
    const violations = (error as { violations?: { field: string; message: string }[] }).violations
    return {
      message: messageOf(error, '免除を登録できませんでした'),
      fields: Object.fromEntries((violations ?? []).map((v) => [v.field, v.message])),
    }
  }

  async function revoke(waiverId: string): Promise<string | null> {
    const { error } = await api.DELETE('/api/v1/waivers/{waiverId}', {
      params: { path: { waiverId } },
    })
    return error ? messageOf(error, '免除を失効させられませんでした') : null
  }

  return { state, items, activeCount, expiringSoonCount, errorMessage, load, create, revoke }
})
