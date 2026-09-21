import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'

export interface CurrentUser {
  userId: string
  githubLogin: string
  displayName?: string | null
  avatarUrl?: string | null
  role: 'ADMIN' | 'VIEWER'
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const loaded = ref(false)

  /**
   * 画面側のロール判定は利便性のためのものであり、防御ではない。
   * 権限の境界は API 側の認可が唯一の担保である。
   */
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const isAuthenticated = computed(() => user.value !== null)

  async function load(): Promise<void> {
    const { data, error } = await api.GET('/api/v1/me')
    user.value = error ? null : ((data ?? null) as CurrentUser | null)
    loaded.value = true
  }

  return { user, loaded, isAdmin, isAuthenticated, load }
})
