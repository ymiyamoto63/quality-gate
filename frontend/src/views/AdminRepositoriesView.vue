<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'
import { useUiStore } from '@/stores/ui'

type Schemas = components['schemas']
type Repository = Schemas['RepositoryItem']

const ui = useUiStore()

const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref<string | null>(null)
const repositories = ref<Repository[]>([])
const announcement = ref('')

// 登録フォーム
const owner = ref('')
const name = ref('')
const defaultBranch = ref('main')
const createError = ref<string | null>(null)

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
  announcement.value = `${data.fullName} を登録しました。収集ランナーの計測プロファイルを追加すると計測できます。`
  await load()
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
            <button type="button" class="qg-button" @click="toggleEnabled(repository)">
              {{ repository.enabled ? '無効化' : '有効化' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
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
h2 {
  margin-top: 0;
}
</style>
