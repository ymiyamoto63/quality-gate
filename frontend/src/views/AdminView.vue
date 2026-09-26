<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { formatDateTime } from '@/api/format'

type Schemas = components['schemas']
type User = Schemas['UserResponse']
type AuditLog = Schemas['AuditLogItem']
type Tab = 'users' | 'audit'

const route = useRoute()
const auth = useAuthStore()
const ui = useUiStore()

const TABS: { id: Tab; label: string; routeName: string }[] = [
  { id: 'users', label: '利用者', routeName: 'users' },
  { id: 'audit', label: '監査ログ', routeName: 'audit-logs' },
]
const tab = computed<Tab>(() => TABS.find((t) => t.routeName === route.name)?.id ?? 'users')
const announcement = ref('')

// 利用者
const users = ref<User[]>([])
const newLogin = ref('')
const newRole = ref<'VIEWER' | 'ADMIN'>('VIEWER')
const userError = ref<string | null>(null)

// 監査ログ
const logs = ref<AuditLog[]>([])
const logCursor = ref<string | null>(null)
const actionFilter = ref('')

// 削除した機能（免除・通知設定・設定の編集・画面からの保持期間の変更）の操作も、過去の監査ログを読めるよう残す
const ACTION_LABELS: Record<string, string> = {
  BOOTSTRAP_ADMIN: '初期管理者の登録',
  USER_ADDED: '利用者の追加',
  USER_ROLE_CHANGED: 'ロールの変更',
  USER_STATUS_CHANGED: '利用者の有効化・無効化',
  REPOSITORY_CREATED: 'リポジトリの登録',
  REPOSITORY_UPDATED: 'リポジトリの更新',
  COMPONENT_DEFINED: 'コンポーネントの定義',
  INGEST_TOKEN_ISSUED: 'トークンの発行',
  INGEST_TOKEN_REVOKED: 'トークンの失効',
  CONFIG_UPDATED: '設定の更新',
  WAIVER_CREATED: '免除の登録',
  WAIVER_REVOKED: '免除の失効',
  WAIVER_EXPIRED: '免除の期限切れ',
  RUN_REEVALUATION_REQUESTED: '再評価の依頼',
  NOTIFICATION_SETTINGS_UPDATED: '通知設定の更新',
  RETENTION_SETTINGS_UPDATED: '保持期間の変更',
  RELEASE_REPORT_EXPORTED: 'リリース判定の CSV 出力',
}

async function loadTab(): Promise<void> {
  if (tab.value === 'users') {
    const { data } = await api.GET('/api/v1/users')
    users.value = data?.items ?? []
  } else {
    await loadLogs(false)
  }
}

onMounted(loadTab)
watch(tab, loadTab)

async function addUser(): Promise<void> {
  userError.value = null
  const { error } = await api.POST('/api/v1/users', {
    body: { githubLogin: newLogin.value.trim(), role: newRole.value },
  })
  if (error) {
    userError.value = messageOf(error, '利用者を追加できませんでした')
    return
  }
  announcement.value = `${newLogin.value} を許可リストに追加しました`
  newLogin.value = ''
  await loadTab()
}

async function updateUser(user: User, body: Schemas['UpdateUserRequest']): Promise<void> {
  const { error } = await api.PATCH('/api/v1/users/{userId}', {
    params: { path: { userId: user.userId } },
    body,
  })
  if (error) {
    ui.notify('error', messageOf(error))
    announcement.value = messageOf(error)
    return
  }
  announcement.value = `${user.githubLogin} を更新しました`
  await loadTab()
}

/**
 * 自分自身は降格・無効化できない。最後の管理者が自分を降格させると、
 * 誰も管理操作を行えなくなる（API 側でも同じ検証をしている）。
 */
function isSelf(user: User): boolean {
  return user.userId === auth.user?.userId
}

async function loadLogs(more: boolean): Promise<void> {
  const { data } = await api.GET('/api/v1/audit-logs', {
    params: {
      query: {
        limit: 50,
        ...(actionFilter.value ? { action: actionFilter.value } : {}),
        ...(more && logCursor.value ? { cursor: logCursor.value } : {}),
      },
    },
  })
  logs.value = more ? [...logs.value, ...(data?.items ?? [])] : (data?.items ?? [])
  logCursor.value = data?.nextCursor ?? null
}

function describe(value: Record<string, unknown> | null | undefined): string {
  if (!value) return '—'
  return Object.entries(value)
    .map(([key, v]) => `${key}: ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join(' / ')
}
</script>

<template>
  <section>
    <h1>管理</h1>
    <p class="qg-visually-hidden" aria-live="polite">{{ announcement }}</p>

    <nav class="qg-tabs" aria-label="管理の項目">
      <RouterLink
        v-for="t in TABS"
        :key="t.id"
        :to="{ name: t.routeName }"
        :aria-current="tab === t.id ? 'page' : undefined"
      >
        {{ t.label }}
      </RouterLink>
      <RouterLink class="qg-tabs__link" :to="{ name: 'admin-repositories' }">
        リポジトリ →
      </RouterLink>
    </nav>

    <template v-if="tab === 'users'">
      <section class="qg-panel" aria-labelledby="add-user-heading">
        <h2 id="add-user-heading">許可リストに追加</h2>
        <p class="qg-muted">
          ここに登録された GitHub ユーザーだけがログインできます。ログイン名の実在は確認しません。
        </p>
        <form class="qg-form qg-form--inline" @submit.prevent="addUser">
          <label>
            GitHub ログイン名
            <input
              v-model="newLogin"
              required
              autocomplete="off"
              :aria-invalid="userError ? 'true' : 'false'"
              aria-describedby="add-user-error"
            />
          </label>
          <label>
            ロール
            <select v-model="newRole">
              <option value="VIEWER">閲覧者（VIEWER）</option>
              <option value="ADMIN">管理者（ADMIN）</option>
            </select>
          </label>
          <div class="qg-form__actions">
            <button type="submit" class="qg-button qg-button--primary">追加</button>
          </div>
        </form>
        <p v-if="userError" id="add-user-error" class="qg-form__error" role="alert">
          {{ userError }}
        </p>
      </section>

      <table class="qg-table qg-table--stack">
        <caption class="qg-visually-hidden">
          許可リストの利用者
        </caption>
        <thead>
          <tr>
            <th scope="col">GitHub ログイン名</th>
            <th scope="col">ロール</th>
            <th scope="col">状態</th>
            <th scope="col">最終ログイン</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.userId">
            <td data-label="GitHub ログイン名">
              {{ user.githubLogin }}
              <span v-if="user.displayName" class="qg-muted">（{{ user.displayName }}）</span>
            </td>
            <td data-label="ロール">
              <select
                :value="user.role"
                :aria-label="`${user.githubLogin} のロール`"
                :disabled="isSelf(user)"
                :title="isSelf(user) ? '自分自身のロールは変更できません' : undefined"
                @change="
                  updateUser(user, {
                    role: ($event.target as HTMLSelectElement).value as 'ADMIN' | 'VIEWER',
                  })
                "
              >
                <option value="VIEWER">閲覧者</option>
                <option value="ADMIN">管理者</option>
              </select>
            </td>
            <td data-label="状態">{{ user.status === 'ACTIVE' ? '有効' : '無効' }}</td>
            <td data-label="最終ログイン">
              {{ user.lastLoginAt ? formatDateTime(user.lastLoginAt) : '未ログイン' }}
            </td>
            <td data-label="操作">
              <button
                type="button"
                class="qg-button"
                :disabled="isSelf(user)"
                :title="isSelf(user) ? '自分自身は無効化できません' : undefined"
                @click="
                  updateUser(user, { status: user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' })
                "
              >
                {{ user.status === 'ACTIVE' ? '無効化' : '有効化' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </template>

    <template v-else-if="tab === 'audit'">
      <form class="qg-form qg-form--inline" @submit.prevent="loadLogs(false)">
        <label>
          操作種別
          <select v-model="actionFilter">
            <option value="">すべて</option>
            <option v-for="(label, action) in ACTION_LABELS" :key="action" :value="action">
              {{ label }}
            </option>
          </select>
        </label>
        <div class="qg-form__actions">
          <button type="submit" class="qg-button">絞り込む</button>
        </div>
      </form>
      <p class="qg-muted">直近 30 日の記録です。監査ログは追記のみで、変更・削除できません。</p>
      <p v-if="logs.length === 0" class="qg-empty">この条件の記録はありません。</p>
      <table v-else class="qg-table qg-table--stack">
        <thead>
          <tr>
            <th scope="col">日時</th>
            <th scope="col">実行者</th>
            <th scope="col">操作</th>
            <th scope="col">対象</th>
            <th scope="col">変更内容</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="log in logs" :key="log.auditLogId">
            <td data-label="日時">{{ formatDateTime(log.occurredAt) }}</td>
            <td data-label="実行者">{{ log.actorLogin ?? '—' }}</td>
            <td data-label="操作">{{ ACTION_LABELS[log.action] ?? log.action }}</td>
            <td data-label="対象">
              {{ log.targetType }} <code v-if="log.targetId">{{ log.targetId.slice(0, 8) }}</code>
            </td>
            <td data-label="変更内容" class="qg-change">
              <span v-if="log.before">前: {{ describe(log.before) }}<br /></span>
              後: {{ describe(log.after) }}
            </td>
          </tr>
        </tbody>
      </table>
      <button v-if="logCursor" type="button" class="qg-button" @click="loadLogs(true)">
        さらに読み込む
      </button>
    </template>
  </section>
</template>

<style scoped>
.qg-form--inline {
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  align-items: end;
  margin-bottom: 1rem;
}
.qg-change {
  word-break: break-word;
  font-size: 0.875rem;
}
.qg-tabs__link {
  margin-left: auto;
  align-self: center;
  font-size: 0.9375rem;
}
h2 {
  font-size: 1.0625rem;
  margin-top: 0;
}
</style>
