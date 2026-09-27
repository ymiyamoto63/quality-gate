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

const lines = computed(() => (config.value?.rawYaml ?? '').split('\n'))

/** 検証エラーは該当行の直下に出す。一覧にまとめると、どの行の話か照合する手間が生まれる。 */
function errorsAt(line: number): ValidationError[] {
  return config.value?.errors.filter((e) => e.line === line) ?? []
}
const unplacedErrors = computed(
  () => config.value?.errors.filter((e) => e.line === null || e.line === undefined) ?? [],
)
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
        <template v-if="config.runId">
          直近の Run（{{ formatDateTime(config.measuredAt) }}）に送られた合格ライン
          <span v-if="config.configCommitSha">
            · quality-gate のコミット {{ shortSha(config.configCommitSha) }}</span
          >
        </template>
        <template v-else>まだ計測がありません。</template>
      </p>
      <p class="qg-panel">
        合格ラインは quality-gate リポジトリの
        <code>collector/targets/&lt;owner&gt;__&lt;name&gt;.gate.yml</code>
        で管理し、収集ランナーが計測のたびに送ります。変更はプルリクエストで行い、main
        にマージした後の計測から使われます。この画面は表示だけで、変更の履歴は Git で確認します。
      </p>
      <p v-if="config.runId && config.rawYaml === null" class="qg-panel" role="status">
        直近の Run には設定ファイルが送られていないため、既定値で判定しています。
      </p>

      <div v-if="config.errors.length > 0" class="qg-panel qg-invalid" role="alert">
        直近に届いた設定ファイルに {{ config.errors.length }} 件の誤りがあり、 その Run
        は判定されていません（処理失敗）。誤りは該当する行の下に表示しています。
        <RouterLink v-if="config.runId" :to="{ name: 'run', params: { runId: config.runId } }">
          Run を見る
        </RouterLink>
        <ul v-if="unplacedErrors.length > 0">
          <li v-for="error in unplacedErrors" :key="error.message">
            {{ error.path }} {{ error.message }}
          </li>
        </ul>
      </div>

      <ol v-if="config.rawYaml" class="qg-yaml" aria-label="設定ファイルの内容">
        <li v-for="(line, index) in lines" :key="index">
          <code>{{ line || ' ' }}</code>
          <p v-for="error in errorsAt(index + 1)" :key="error.message" class="qg-yaml__error">
            <i class="pi pi-exclamation-triangle" aria-hidden="true" />
            <span class="qg-visually-hidden">{{ index + 1 }} 行目の誤り: </span>
            {{ error.message }}
          </p>
        </li>
      </ol>
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
