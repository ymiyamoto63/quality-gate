import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, readCookie } from '@/api/client'

/**
 * ログインの状態。アカウントは全員で共有する 1 つだけで、ロールは無い。
 *
 * ログインとログアウトは Spring Security のフォームログインの口（/api/v1/login・/api/v1/logout）を使う。
 * フォームの送信なので openapi-fetch の型には現れず、fetch で直接送る。
 */
export const useAuthStore = defineStore('auth', () => {
  const username = ref<string | null>(null)
  const loaded = ref(false)
  const isAuthenticated = computed(() => username.value !== null)

  async function load(): Promise<void> {
    const { data, error } = await api.GET('/api/v1/me')
    username.value = error || !data ? null : data.username
    loaded.value = true
  }

  /** @returns ログインできたか */
  async function login(user: string, password: string): Promise<boolean> {
    const response = await fetch('/api/v1/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: csrfHeaders(),
      body: new URLSearchParams({ username: user, password }),
    })
    if (!response.ok) return false
    await load()
    return isAuthenticated.value
  }

  async function logout(): Promise<void> {
    await fetch('/api/v1/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: csrfHeaders(),
    })
    username.value = null
  }

  return { username, loaded, isAuthenticated, load, login, logout }
})

/** CSRF のトークンは、先に GET した応答で受け取った XSRF-TOKEN Cookie の値を送り返す。 */
function csrfHeaders(): Record<string, string> {
  const token = readCookie('XSRF-TOKEN')
  return token ? { 'X-XSRF-TOKEN': token } : {}
}
