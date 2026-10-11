import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { handleSessionExpired, router } from './index'

// 画面の部品は描かない。遷移先の判定だけを確かめる
vi.mock('@/views/ReleaseView.vue', () => ({ default: {} }))
vi.mock('@/views/LoginView.vue', () => ({ default: {} }))
vi.mock('@/views/NotFoundView.vue', () => ({ default: {} }))

describe('セッション切れ（API の 401）', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.loaded = true
    auth.username = 'quality'
    await router.replace('/login')
  })

  it('ログイン画面へ移し、開いていた判定を next に残す', async () => {
    await router.push('/?ref=v1.2.0')

    await handleSessionExpired()

    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.next).toBe('/?ref=v1.2.0')
  })

  it('最新の計測を見ていたときは next を付けない', async () => {
    await router.push('/')

    await handleSessionExpired()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query).toEqual({})
  })

  it('ログイン画面にいるときは移らない', async () => {
    await router.replace({ name: 'login', query: { next: '/?ref=abc1234' } })

    await handleSessionExpired()

    expect(router.currentRoute.value.query.next).toBe('/?ref=abc1234')
  })
})
