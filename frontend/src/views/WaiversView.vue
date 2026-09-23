<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppDialog from '@/components/AppDialog.vue'
import WaiverForm from '@/components/WaiverForm.vue'
import { api } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { useWaiverStore, type WaiverItem, type WaiverStatus } from '@/stores/waivers'
import { formatDateTime } from '@/api/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()
const store = useWaiverStore()

const STATUS_LABELS: Record<WaiverStatus, string> = {
  ACTIVE: '有効',
  EXPIRED: '期限切れ',
  REVOKED: '失効',
}
const METRICS = [
  ['M-01', 'ブランチカバレッジ'],
  ['M-02', 'ミューテーションスコア'],
  ['M-03', '応答時間 p95'],
  ['M-04', 'スループット'],
  ['M-05', 'エラー率'],
  ['M-06', '重大・高 脆弱性件数'],
  ['M-07', '循環的複雑度 15 超の新規関数数'],
  ['M-08', 'API 契約テスト成功率'],
  ['M-09', '破壊的変更件数'],
  ['M-10', 'アクセシビリティ違反'],
] as const

const status = ref<WaiverStatus | ''>('ACTIVE')
const repositoryId = computed(() =>
  typeof route.query.repositoryId === 'string' ? route.query.repositoryId : null,
)
const repositories = ref<{ repositoryId: string; fullName: string }[]>([])
const announcement = ref('')

/** 違反一覧の「免除を登録」から来たときの対象。 */
const findingTarget = computed(() => {
  const q = route.query
  if (q.register !== 'finding' || typeof q.fingerprint !== 'string') return null
  return {
    repositoryId: String(q.repositoryId),
    metricId: String(q.metricId),
    fingerprint: q.fingerprint,
    title: typeof q.title === 'string' ? q.title : q.fingerprint,
  }
})

const metricDialogOpen = ref(false)
const metricRepositoryId = ref('')
const metricId = ref('M-06')
const metricTitle = computed(
  () => `${METRICS.find(([id]) => id === metricId.value)?.[1] ?? metricId.value}（指標全体）`,
)

async function load(): Promise<void> {
  await store.load(repositoryId.value, status.value || null)
}

onMounted(async () => {
  await load()
  const { data } = await api.GET('/api/v1/repositories')
  repositories.value = data?.items ?? []
  metricRepositoryId.value = repositoryId.value ?? repositories.value[0]?.repositoryId ?? ''
})
watch([status, repositoryId], load)

function daysLeft(item: WaiverItem): number {
  return Math.max(0, Math.ceil((new Date(item.expiresAt).getTime() - Date.now()) / 86_400_000))
}

async function revoke(item: WaiverItem): Promise<void> {
  if (
    !window.confirm(
      `「${item.title ?? item.metricName}」の免除を失効させますか？次の判定から再び数えられます。`,
    )
  ) {
    return
  }
  const error = await store.revoke(item.waiverId)
  if (error) {
    ui.notify('error', error)
    return
  }
  announcement.value = '免除を失効させました。最新の Run を再評価しています。'
  await load()
}

async function closeFindingDialog(registered: boolean): Promise<void> {
  const query = { ...route.query }
  for (const key of ['register', 'metricId', 'fingerprint', 'title']) delete query[key]
  await router.replace({ query })
  if (registered) await onRegistered()
}

async function onRegistered(): Promise<void> {
  metricDialogOpen.value = false
  announcement.value = '免除を登録しました。最新の Run を再評価しています。'
  await load()
}

function clearRepositoryFilter(): void {
  void router.replace({ query: {} })
}
</script>

<template>
  <section>
    <div class="qg-head">
      <h1>免除管理</h1>
      <div class="qg-head__actions">
        <label class="qg-inline">
          状態
          <select v-model="status">
            <option value="ACTIVE">有効</option>
            <option value="EXPIRED">期限切れ</option>
            <option value="REVOKED">失効</option>
            <option value="">すべて</option>
          </select>
        </label>
        <button
          type="button"
          class="qg-button"
          :disabled="!auth.isAdmin || repositories.length === 0"
          :aria-describedby="auth.isAdmin ? undefined : 'waiver-admin-note'"
          @click="metricDialogOpen = true"
        >
          指標全体を免除…
        </button>
      </div>
    </div>
    <p v-if="!auth.isAdmin" id="waiver-admin-note" class="qg-muted">
      免除の登録・失効には管理者権限が必要です。違反の免除は Run の違反一覧から登録します。
    </p>

    <p v-if="repositoryId" class="qg-muted">
      リポジトリで絞り込み中
      <button type="button" class="qg-button" @click="clearRepositoryFilter">解除</button>
    </p>

    <p class="qg-summary" role="status">
      有効 {{ store.activeCount }} 件（うち 7 日以内に期限切れ {{ store.expiringSoonCount }} 件）
    </p>
    <p class="qg-visually-hidden" aria-live="polite">{{ announcement }}</p>

    <p v-if="store.state === 'loading'" class="qg-muted">読み込み中…</p>
    <p v-else-if="store.state === 'error'" role="alert">
      {{ store.errorMessage }}
      <button type="button" class="qg-button" @click="load">再試行</button>
    </p>
    <p v-else-if="store.items.length === 0" class="qg-empty">
      この条件の免除はありません。免除は Run の違反一覧から、違反ごとに登録できます。
    </p>

    <!--
      理由の全文を折りたたまずに出す。免除の価値は「誰が・何を・なぜ・いつまで」が
      追えることにあり、理由が隠れていると判断の妥当性を検証できない（docs/08 4.7）。
    -->
    <ul v-else class="qg-waivers">
      <li
        v-for="item in store.items"
        :key="item.waiverId"
        class="qg-panel"
        :data-expiring="item.expiringSoon ? 'true' : undefined"
      >
        <p class="qg-waiver__head">
          <span v-if="item.status === 'ACTIVE'" class="qg-badge" :data-soon="item.expiringSoon">
            <i class="pi pi-clock" aria-hidden="true" />
            残り {{ daysLeft(item) }} 日
          </span>
          <span v-else class="qg-badge">{{ STATUS_LABELS[item.status] }}</span>
          <strong>{{ item.title ?? item.metricName }}</strong>
        </p>
        <p class="qg-muted">
          {{ item.repositoryFullName }} · {{ item.metricId }} {{ item.metricName }}
          <span v-if="item.scope === 'METRIC'">（指標全体）</span>
          · {{ item.reasonCategoryLabel }} · 登録: @{{ item.createdByLogin ?? '不明' }}
          {{ formatDateTime(item.createdAt) }} · 期限 {{ formatDateTime(item.expiresAt) }}
          <span v-if="item.revokedAt">
            · 失効: @{{ item.revokedByLogin ?? '不明' }} {{ formatDateTime(item.revokedAt) }}
          </span>
        </p>
        <blockquote class="qg-reason">{{ item.reason }}</blockquote>
        <p v-if="item.status === 'ACTIVE'" class="qg-waiver__actions">
          <button
            type="button"
            class="qg-button"
            :disabled="!auth.isAdmin"
            :title="auth.isAdmin ? undefined : 'この操作には管理者権限が必要です'"
            @click="revoke(item)"
          >
            失効させる
          </button>
        </p>
      </li>
    </ul>

    <AppDialog
      :open="findingTarget !== null && auth.isAdmin"
      title="免除を登録"
      @close="closeFindingDialog(false)"
    >
      <WaiverForm
        v-if="findingTarget"
        scope="FINDING"
        :repository-id="findingTarget.repositoryId"
        :metric-id="findingTarget.metricId"
        :fingerprint="findingTarget.fingerprint"
        :title="findingTarget.title"
        @done="closeFindingDialog(true)"
        @cancel="closeFindingDialog(false)"
      />
    </AppDialog>

    <AppDialog :open="metricDialogOpen" title="指標全体を免除" @close="metricDialogOpen = false">
      <div class="qg-form">
        <label>
          リポジトリ
          <select v-model="metricRepositoryId">
            <option v-for="r in repositories" :key="r.repositoryId" :value="r.repositoryId">
              {{ r.fullName }}
            </option>
          </select>
        </label>
        <label>
          指標
          <select v-model="metricId">
            <option v-for="[id, name] in METRICS" :key="id" :value="id">{{ id }} {{ name }}</option>
          </select>
        </label>
        <p class="qg-muted">
          指標の免除を設定している間は、ダッシュボードに常に警告が表示されます。
        </p>
      </div>
      <WaiverForm
        v-if="metricDialogOpen && metricRepositoryId"
        :key="`${metricRepositoryId}/${metricId}`"
        scope="METRIC"
        :repository-id="metricRepositoryId"
        :metric-id="metricId"
        :title="metricTitle"
        @done="onRegistered"
        @cancel="metricDialogOpen = false"
      />
    </AppDialog>
  </section>
</template>

<style scoped>
.qg-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem 1rem;
}
.qg-head__actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
}
.qg-inline select {
  margin-left: 0.4rem;
  font: inherit;
}
.qg-summary {
  font-weight: 600;
}
.qg-waivers {
  list-style: none;
  padding: 0;
}
.qg-panel[data-expiring='true'] {
  border-left: 4px solid var(--status-warn);
}
.qg-waiver__head {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  margin-top: 0;
}
.qg-badge {
  border: 1px solid var(--axis);
  border-radius: 999px;
  padding: 0.1rem 0.6rem;
  font-size: 0.8125rem;
}
.qg-badge[data-soon='true'] {
  border-color: var(--status-warn);
  border-width: 2px;
}
.qg-reason {
  margin: 0.5rem 0;
  padding-left: 0.75rem;
  border-left: 3px solid var(--border);
  white-space: pre-wrap;
}
.qg-waiver__actions {
  margin-bottom: 0;
}
</style>
