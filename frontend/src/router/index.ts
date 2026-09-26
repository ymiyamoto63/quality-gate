import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import DashboardView from '@/views/DashboardView.vue'
import LoginView from '@/views/LoginView.vue'
import ForbiddenView from '@/views/ForbiddenView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import RepositoryDetailView from '@/views/RepositoryDetailView.vue'
import ConfigView from '@/views/ConfigView.vue'
import AdminRepositoriesView from '@/views/AdminRepositoriesView.vue'
import AdminView from '@/views/AdminView.vue'
import RunDetailView from '@/views/RunDetailView.vue'
import FindingListView from '@/views/FindingListView.vue'
import TrendView from '@/views/TrendView.vue'
import ReportView from '@/views/ReportView.vue'

/** 画面一覧は docs/initial/08-screen-design.md 1 章と対応する。 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { public: true, title: 'ログイン' },
  },
  {
    path: '/forbidden',
    name: 'forbidden',
    component: ForbiddenView,
    meta: { public: true, title: 'アクセス拒否' },
  },

  { path: '/', name: 'dashboard', component: DashboardView, meta: { title: 'ダッシュボード' } },

  {
    path: '/repositories/:repositoryId',
    name: 'repository',
    component: RepositoryDetailView,
    meta: { title: 'リポジトリ詳細' },
  },
  {
    path: '/repositories/:repositoryId/trends',
    name: 'trends',
    component: TrendView,
    meta: { title: 'トレンド' },
  },
  {
    path: '/repositories/:repositoryId/config',
    name: 'config',
    component: ConfigView,
    meta: { title: '設定' },
  },
  { path: '/runs/:runId', name: 'run', component: RunDetailView, meta: { title: 'Run 詳細' } },
  {
    path: '/runs/:runId/findings',
    name: 'findings',
    component: FindingListView,
    meta: { title: '違反一覧' },
  },
  { path: '/reports', name: 'reports', component: ReportView, meta: { title: '品質レポート' } },
  {
    path: '/admin/repositories',
    name: 'admin-repositories',
    component: AdminRepositoriesView,
    meta: { title: 'リポジトリ管理', adminOnly: true },
  },
  {
    path: '/admin/users',
    name: 'users',
    component: AdminView,
    meta: { title: '利用者管理', adminOnly: true },
  },
  {
    path: '/admin/audit-logs',
    name: 'audit-logs',
    component: AdminView,
    meta: { title: '監査ログ', adminOnly: true },
  },
  {
    path: '/admin/retention',
    name: 'retention',
    component: AdminView,
    meta: { title: '保持期間', adminOnly: true },
  },
  {
    path: '/admin/jobs',
    name: 'jobs',
    component: AdminView,
    meta: { title: '失敗したジョブ', adminOnly: true },
  },

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
    return { name: 'login' }
  }
  // 画面側の制御は利便性のためのもの。権限の境界は API 側が担保する。
  if (to.meta.adminOnly && !auth.isAdmin) {
    return { name: 'forbidden' }
  }
  return true
})

// 画面遷移でタイトルを更新する（docs/08 A-12）
router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : null
  document.title = title ? `${title} | quality-gate` : 'quality-gate'
})
