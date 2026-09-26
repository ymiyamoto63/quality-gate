<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'
import { formatDateTime, shortSha } from '@/api/format'

type Schemas = components['schemas']
type ValidationError = Schemas['ValidationErrorItem']

const route = useRoute()
const repositoryId = computed(() => String(route.params.repositoryId))

const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref<string | null>(null)
const config = ref<Schemas['RepositoryConfig'] | null>(null)
const tab = ref<'file' | 'history'>('file')

async function load(): Promise<void> {
  state.value = 'loading'
  const { data, error } = await api.GET('/api/v1/repositories/{repositoryId}/config', {
    params: { path: { repositoryId: repositoryId.value } },
  })
  if (error) {
    state.value = 'error'
    errorMessage.value = messageOf(error, '設定を取得できませんでした')
    return
  }
  config.value = data
  state.value = 'ready'
}

onMounted(load)
watch(repositoryId, load)

/**
 * 表示する YAML。直近に届いた設定が検証エラーなら、その内容を出す
 * （現在の版ではなく、直したい誤りのあるファイルを見せる）。
 */
const shownYaml = computed(() => {
  const value = config.value
  if (!value) return ''
  if (!value.validation.valid && value.validation.rawYaml) return value.validation.rawYaml
  return value.current?.rawYaml ?? value.defaultYaml
})

const lines = computed(() => shownYaml.value.split('\n'))

/** 検証エラーは該当行の直下に出す。一覧にまとめると、どの行の話か照合する手間が生まれる。 */
function errorsAt(line: number): ValidationError[] {
  return config.value?.validation.errors.filter((e) => e.line === line) ?? []
}
const unplacedErrors = computed(
  () =>
    config.value?.validation.errors.filter((e) => e.line === null || e.line === undefined) ?? [],
)

function sourceLabel(sourceType: string): string {
  if (sourceType === 'FILE') return '設定ファイル'
  if (sourceType === 'UI') return '画面から設定（以前の方式）'
  return '既定値'
}
</script>

<template>
  <section>
    <h1>設定</h1>

    <p v-if="state === 'loading'" class="qg-muted">読み込み中…</p>
    <p v-else-if="state === 'error'" role="alert">
      {{ errorMessage }}
      <button type="button" class="qg-button" @click="load">再試行</button>
    </p>

    <template v-else-if="config">
      <p class="qg-muted">
        <template v-if="config.current">
          現在: v{{ config.current.version }}（{{ sourceLabel(config.current.sourceType) }}
          <span v-if="config.current.sourceCommitSha">
            · コミット {{ shortSha(config.current.sourceCommitSha) }}</span
          >
          · {{ formatDateTime(config.current.createdAt) }}）
        </template>
        <template v-else>設定の版はまだありません。システムの既定値で判定しています。</template>
      </p>
      <p class="qg-panel">
        設定は quality-gate リポジトリの
        <code>collector/targets/&lt;owner&gt;__&lt;name&gt;.gate.yml</code>
        で管理し、収集ランナーが計測のたびに送ります。変更はプルリクエストで行い、main
        にマージした後の計測から使われます。この画面は表示だけです。
      </p>

      <div class="qg-tabs" role="tablist" aria-label="設定の表示">
        <button type="button" role="tab" :aria-selected="tab === 'file'" @click="tab = 'file'">
          ファイルの内容
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="tab === 'history'"
          @click="tab = 'history'"
        >
          変更履歴（{{ config.history.length }}）
        </button>
      </div>

      <div v-if="tab === 'file'" role="tabpanel">
        <div v-if="!config.validation.valid" class="qg-panel qg-invalid" role="alert">
          直近に届いた設定ファイルに {{ config.validation.errors.length }} 件の誤りがあり、 その Run
          は判定されていません（処理失敗）。誤りは該当する行の下に表示しています。
          <RouterLink
            v-if="config.validation.runId"
            :to="{ name: 'run', params: { runId: config.validation.runId } }"
          >
            Run を見る
          </RouterLink>
          <ul v-if="unplacedErrors.length > 0">
            <li v-for="error in unplacedErrors" :key="error.message">
              {{ error.path }} {{ error.message }}
            </li>
          </ul>
        </div>

        <ol class="qg-yaml" aria-label="設定ファイルの内容">
          <li v-for="(line, index) in lines" :key="index">
            <code>{{ line || ' ' }}</code>
            <p v-for="error in errorsAt(index + 1)" :key="error.message" class="qg-yaml__error">
              <i class="pi pi-exclamation-triangle" aria-hidden="true" />
              <span class="qg-visually-hidden">{{ index + 1 }} 行目の誤り: </span>
              {{ error.message }}
            </p>
          </li>
        </ol>
      </div>

      <div v-else-if="tab === 'history'" role="tabpanel">
        <p v-if="config.history.length === 0" class="qg-empty">変更履歴はまだありません。</p>
        <table v-else class="qg-table qg-table--stack">
          <thead>
            <tr>
              <th scope="col">版</th>
              <th scope="col">取得元</th>
              <th scope="col">コミット</th>
              <th scope="col">登録日時</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in config.history" :key="item.gateConfigId">
              <td data-label="版">v{{ item.version }}</td>
              <td data-label="取得元">{{ sourceLabel(item.sourceType) }}</td>
              <td data-label="コミット">
                {{ item.sourceCommitSha ? shortSha(item.sourceCommitSha) : '—' }}
              </td>
              <td data-label="登録日時">{{ formatDateTime(item.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped>
.qg-yaml {
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.75rem 0.75rem 0.75rem 3.5rem;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.875rem;
  overflow-x: auto;
}
.qg-yaml li::marker {
  color: var(--text-muted);
}
.qg-yaml code {
  white-space: pre;
}
.qg-yaml__error {
  margin: 0.25rem 0;
  font-family: var(--font-sans);
  border-left: 3px solid var(--status-warn);
  padding-left: 0.5rem;
}
.qg-invalid {
  border-color: var(--status-error);
}
</style>
