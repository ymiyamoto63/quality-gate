import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import ReleaseView from '@/views/ReleaseView.vue'
import LoginView from '@/views/LoginView.vue'
import NotFoundView from '@/views/NotFoundView.vue'

/**
 * 画面はリリース判定の 1 つだけ（docs/architecture.md 7.1）。
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

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.loaded) {
    await auth.load()
  }

  if (to.meta.public) {
    return true
  }
  if (!auth.isAuthenticated) {
    // ログインの後に、開こうとしていた判定へ戻す
    return { name: 'login', query: to.fullPath === '/' ? {} : { next: to.fullPath } }
  }
  return true
})

// 画面遷移でタイトルを更新する（docs/architecture.md 7.7 A-12）
router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : null
  document.title = title ? `${title} | quality-gate` : 'quality-gate'
})
