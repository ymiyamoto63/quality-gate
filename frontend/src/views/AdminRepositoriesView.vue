<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppDialog from '@/components/AppDialog.vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'
import { useUiStore } from '@/stores/ui'
import { formatDateTime } from '@/api/format'

type Schemas = components['schemas']
type Repository = Schemas['RepositoryItem']
type Token = Schemas['TokenSummary']

const ui = useUiStore()

const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref<string | null>(null)
const repositories = ref<Repository[]>([])
const selected = ref<Repository | null>(null)
const tokens = ref<Token[]>([])
const announcement = ref('')

// 登録フォーム
const owner = ref('')
const name = ref('')
const defaultBranch = ref('main')
const createError = ref<string | null>(null)

// コンポーネント定義
const componentName = ref('')
const componentLanguage = ref('java')
const componentPaths = ref('')

// 発行したトークン（一度だけ表示する）
const issuedToken = ref<string | null>(null)
const copied = ref(false)

async function load(): Promise<void> {
  state.value = 'loading'
  const { data, error } = await api.GET('/api/v1/repositories')
  if (error) {
    state.value = 'error'
    errorMessage.value = messageOf(error, 'リポジトリを取得できませんでした')
    return
  }
  repositories.value = data.items
  state.value = 'ready'
}

onMounted(load)

async function create(): Promise<void> {
  createError.value = null
  const { data, error } = await api.POST('/api/v1/repositories', {
    body: {
      owner: owner.value.trim(),
      name: name.value.trim(),
      defaultBranch: defaultBranch.value.trim(),
    },
  })
  if (error) {
    createError.value = messageOf(error, 'リポジトリを登録できませんでした')
    return
  }
  owner.value = ''
  name.value = ''
  announcement.value = `${data.fullName} を登録しました。続けて Ingest Token を発行してください。`
  await load()
  await select(repositories.value.find((r) => r.repositoryId === data.repositoryId) ?? null)
}

async function select(repository: Repository | null): Promise<void> {
  selected.value = repository
  tokens.value = []
  if (!repository) return
  const { data } = await api.GET('/api/v1/repositories/{repositoryId}/ingest-tokens', {
    params: { path: { repositoryId: repository.repositoryId } },
  })
  tokens.value = data?.items ?? []
}

async function toggleEnabled(repository: Repository): Promise<void> {
  const next = !repository.enabled
  if (
    !next &&
    !window.confirm(
      `${repository.fullName} を無効化しますか？ 計測結果を受け付けなくなり、ダッシュボードから外れます。`,
    )
  ) {
    return
  }
  const { error } = await api.PATCH('/api/v1/repositories/{repositoryId}', {
    params: { path: { repositoryId: repository.repositoryId } },
    body: { enabled: next },
  })
  if (error) {
    ui.notify('error', messageOf(error))
    return
  }
  await load()
  await select(repositories.value.find((r) => r.repositoryId === repository.repositoryId) ?? null)
}

async function issueToken(): Promise<void> {
  if (!selected.value) return
  const { data, error } = await api.POST('/api/v1/repositories/{repositoryId}/ingest-tokens', {
    params: { path: { repositoryId: selected.value.repositoryId } },
    body: { description: 'GitHub Actions' },
  })
  if (error) {
    ui.notify('error', messageOf(error, 'トークンを発行できませんでした'))
    return
  }
  copied.value = false
  issuedToken.value = data.token
}

async function copyToken(): Promise<void> {
  if (!issuedToken.value) return
  try {
    await navigator.clipboard.writeText(issuedToken.value)
    copied.value = true
    announcement.value = 'トークンをコピーしました'
  } catch {
    // クリップボードが使えない環境では、表示された値を手で選択してコピーしてもらう
    announcement.value = 'コピーできませんでした。値を選択してコピーしてください'
  }
}

/** 手でコピーした場合の逃げ道。コピー操作をしたことを利用者に宣言させる。 */
function confirmCopiedManually(): void {
  copied.value = true
}

async function closeTokenDialog(): Promise<void> {
  // 平文は二度と取得できない。閉じたら画面からも消す
  issuedToken.value = null
  if (selected.value) await select(selected.value)
}

async function revoke(token: Token): Promise<void> {
  if (
    !window.confirm(
      `トークン ${token.tokenPrefix} を失効させますか？ このトークンを使う収集ランナーや CI は即座に送信できなくなります。`,
    )
  ) {
    return
  }
  const { error } = await api.DELETE('/api/v1/ingest-tokens/{tokenId}', {
    params: { path: { tokenId: token.tokenId } },
  })
  if (error) {
    ui.notify('error', messageOf(error))
    return
  }
  announcement.value = `トークン ${token.tokenPrefix} を失効させました`
  if (selected.value) await select(selected.value)
}

async function defineComponent(): Promise<void> {
  if (!selected.value) return
  const patterns = componentPaths.value
    .split(/[\n,]/)
    .map((p) => p.trim())
    .filter(Boolean)
  const { error } = await api.POST('/api/v1/repositories/{repositoryId}/components', {
    params: { path: { repositoryId: selected.value.repositoryId } },
    body: {
      name: componentName.value.trim(),
      language: componentLanguage.value.trim(),
      pathPatterns: patterns,
    },
  })
  if (error) {
    ui.notify('error', messageOf(error, 'コンポーネントを定義できませんでした'))
    return
  }
  announcement.value = `コンポーネント ${componentName.value} を定義しました`
  componentName.value = ''
  componentPaths.value = ''
}
</script>

<template>
  <section>
    <h1>リポジトリ管理</h1>
    <p class="qg-visually-hidden" aria-live="polite">{{ announcement }}</p>

    <section class="qg-panel" aria-labelledby="register-heading">
      <h2 id="register-heading">リポジトリを登録</h2>
      <form class="qg-form qg-form--inline" @submit.prevent="create">
        <label>
          owner
          <input v-model="owner" required autocomplete="off" placeholder="ymiyamoto63" />
        </label>
        <label>
          リポジトリ名
          <input v-model="name" required autocomplete="off" placeholder="quality-gate" />
        </label>
        <label>
          監視対象ブランチ
          <input v-model="defaultBranch" required autocomplete="off" />
        </label>
        <div class="qg-form__actions">
          <button type="submit" class="qg-button qg-button--primary">登録</button>
        </div>
      </form>
      <p v-if="createError" class="qg-form__error" role="alert">{{ createError }}</p>
    </section>

    <p v-if="state === 'loading'" class="qg-muted">読み込み中…</p>
    <p v-else-if="state === 'error'" role="alert">
      {{ errorMessage }} <button type="button" class="qg-button" @click="load">再試行</button>
    </p>
    <p v-else-if="repositories.length === 0" class="qg-empty">
      登録済みのリポジトリはありません。上のフォームから登録してください。
    </p>

    <table v-else class="qg-table qg-table--stack">
      <caption class="qg-visually-hidden">
        登録済みのリポジトリ
      </caption>
      <thead>
        <tr>
          <th scope="col">リポジトリ</th>
          <th scope="col">監視対象ブランチ</th>
          <th scope="col">状態</th>
          <th scope="col">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="repository in repositories" :key="repository.repositoryId">
          <td data-label="リポジトリ">
            <RouterLink
              :to="{ name: 'repository', params: { repositoryId: repository.repositoryId } }"
            >
              {{ repository.fullName }}
            </RouterLink>
          </td>
          <td data-label="監視対象ブランチ">{{ repository.defaultBranch }}</td>
          <td data-label="状態">{{ repository.enabled ? '有効' : '無効' }}</td>
          <td data-label="操作" class="qg-actions">
            <button type="button" class="qg-button" @click="select(repository)">
              トークン・コンポーネントを管理
            </button>
            <button type="button" class="qg-button" @click="toggleEnabled(repository)">
              {{ repository.enabled ? '無効化' : '有効化' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <template v-if="selected">
      <h2 class="qg-selected">{{ selected.fullName }}</h2>

      <section class="qg-panel" aria-labelledby="tokens-heading">
        <h3 id="tokens-heading">Ingest Token</h3>
        <p class="qg-muted">
          収集ランナー（または CI）が計測結果を送るためのトークンです。書き込み専用で、参照 API
          には使えません。
        </p>
        <button type="button" class="qg-button qg-button--primary" @click="issueToken">
          トークンを発行
        </button>
        <table v-if="tokens.length > 0" class="qg-table qg-table--stack">
          <thead>
            <tr>
              <th scope="col">prefix</th>
              <th scope="col">用途</th>
              <th scope="col">発行</th>
              <th scope="col">最終使用</th>
              <th scope="col">状態</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="token in tokens" :key="token.tokenId">
              <td data-label="prefix">
                <code>qg_{{ token.tokenPrefix }}_…</code>
              </td>
              <td data-label="用途">{{ token.description ?? '—' }}</td>
              <td data-label="発行">{{ formatDateTime(token.createdAt) }}</td>
              <td data-label="最終使用">
                {{ token.lastUsedAt ? formatDateTime(token.lastUsedAt) : '未使用' }}
              </td>
              <td data-label="状態">
                <template v-if="token.revokedAt"
                  >失効（{{ formatDateTime(token.revokedAt) }}）</template
                >
                <button v-else type="button" class="qg-button" @click="revoke(token)">
                  失効させる
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <section class="qg-panel" aria-labelledby="components-heading">
        <h3 id="components-heading">コンポーネント定義</h3>
        <form class="qg-form" @submit.prevent="defineComponent">
          <label>
            名前
            <input v-model="componentName" required placeholder="backend" />
          </label>
          <label>
            言語
            <input v-model="componentLanguage" required />
          </label>
          <label>
            パス（glob、1 行に 1 件）
            <textarea v-model="componentPaths" rows="2" required placeholder="backend/**" />
          </label>
          <div class="qg-form__actions">
            <button type="submit" class="qg-button">定義を保存</button>
          </div>
        </form>
      </section>
    </template>

    <!--
      発行したトークンは一度しか表示しない。コピーするまで閉じられず、
      Esc と背景クリックでも閉じない（docs/initial/08-screen-design.md 4.8）。
    -->
    <AppDialog
      :open="issuedToken !== null"
      title="Ingest Token を発行しました"
      :dismissible="false"
    >
      <p class="qg-form__error">
        この値が表示されるのはこの一度だけです。閉じると二度と取得できません。
      </p>
      <p class="qg-token">
        <code>{{ issuedToken }}</code>
        <button type="button" class="qg-button" @click="copyToken">コピー</button>
      </p>
      <p>GitHub の Secrets に <code>QG_INGEST_TOKEN</code> として登録してください。</p>
      <p>
        <button type="button" class="qg-link-button" @click="confirmCopiedManually">
          手動でコピーしました
        </button>
      </p>
      <div class="qg-form__actions">
        <button
          type="button"
          class="qg-button qg-button--primary"
          :disabled="!copied"
          @click="closeTokenDialog"
        >
          コピーしました
        </button>
      </div>
    </AppDialog>
  </section>
</template>

<style scoped>
.qg-form--inline {
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  align-items: end;
}
.qg-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.qg-selected {
  margin-top: 2rem;
}
h2,
h3 {
  margin-top: 0;
}
.qg-token {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}
.qg-token code {
  word-break: break-all;
  user-select: all;
  padding: 0.4rem;
  border: 1px solid var(--axis);
  border-radius: var(--radius);
}
.qg-link-button {
  background: none;
  border: none;
  padding: 0;
  color: var(--link);
  text-decoration: underline;
  font: inherit;
  cursor: pointer;
}
</style>
