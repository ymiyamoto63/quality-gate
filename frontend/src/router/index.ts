import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { onUnauthorized } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import ReleaseView from '@/views/ReleaseView.vue'
import LoginView from '@/views/LoginView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import TrendView from '@/views/TrendView.vue'

/**
 * 画面はリリース判定と、その補助の指標の推移（docs/architecture.md 7.1）。
 * 見るタグ・コミットは ?ref= に置き、同じ判定を URL で共有できるようにする。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { public: true, title: 'ログイン' },
  },
  { path: '/', name: 'release', component: ReleaseView, meta: { title: 'リリース判定' } },
  { path: '/trends', name: 'trends', component: TrendView, meta: { title: '指標の推移' } },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: NotFoundView,
    meta: { title: 'ページが見つかりません' },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

/** ログイン画面へ。ログインの後に、開こうとしていた判定へ戻す */
function toLogin(fullPath: string) {
  return { name: 'login', query: fullPath === '/' ? {} : { next: fullPath } }
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.loaded) {
    await auth.load()
  }

  if (to.meta.public) {
    return true
  }
  if (!auth.isAuthenticated) {
    return toLogin(to.fullPath)
  }
  return true
})

/**
 * 開いたままの画面でセッションが切れたとき（API の 401）。ログイン画面へ移し、ログインの後に元の画面へ戻す。
 * 画面を開き直さないとログイン画面に移らず、判定が「取得できませんでした」のまま見えなくなるのを防ぐ。
 */
export async function handleSessionExpired(): Promise<void> {
  useAuthStore().expire()
  const current = router.currentRoute.value
  if (current.meta.public) {
    return
  }
  await router.replace(toLogin(current.fullPath))
}

onUnauthorized(() => void handleSessionExpired())

// 画面遷移でタイトルを更新する（docs/architecture.md 7.7 A-12）
router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : null
  document.title = title ? `${title} | quality-gate` : 'quality-gate'
})
