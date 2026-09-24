<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, formatValue, shortSha } from '@/api/format'
import StatusChip from '@/components/StatusChip.vue'
import { verdictToStatus, type MeasurementStatus, type Verdict } from '@/api/status'

type Schemas = components['schemas']
type ValidationError = Schemas['ValidationErrorItem']

const route = useRoute()
const auth = useAuthStore()
const repositoryId = computed(() => String(route.params.repositoryId))

const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref<string | null>(null)
const config = ref<Schemas['RepositoryConfig'] | null>(null)
const tab = ref<'file' | 'history' | 'edit'>('file')

const draft = ref('')
const saving = ref(false)
const saveErrors = ref<ValidationError[]>([])
const saveMessage = ref<string | null>(null)

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
  draft.value = data.current?.rawYaml ?? data.defaultYaml
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

async function save(): Promise<void> {
  saving.value = true
  saveErrors.value = []
  saveMessage.value = null
  const { data, error } = await api.PUT('/api/v1/repositories/{repositoryId}/config', {
    params: { path: { repositoryId: repositoryId.value } },
    body: { rawYaml: draft.value },
  })
  saving.value = false
  if (error) {
    const problem = error as { errors?: ValidationError[] }
    saveErrors.value = problem.errors ?? []
    saveMessage.value = messageOf(error, '設定を保存できませんでした')
    return
  }
  config.value = data
  saveMessage.value = `v${data.current?.version} として保存しました。次の判定から使われます。`
}

/**
 * 設定の変更を過去の Run で試算する（ドライラン。FR-02-5）。保存はしない。
 * 保存する前に「どの Run の判定が変わるか」を確かめられるようにする。
 */
const dryRunning = ref(false)
const dryRun = ref<Schemas['DryRunResponse'] | null>(null)

async function simulate(): Promise<void> {
  dryRunning.value = true
  saveErrors.value = []
  saveMessage.value = null
  dryRun.value = null
  const { data, error } = await api.POST('/api/v1/repositories/{repositoryId}/config/dry-run', {
    params: { path: { repositoryId: repositoryId.value } },
    body: { rawYaml: draft.value },
  })
  dryRunning.value = false
  if (error) {
    const problem = error as { errors?: ValidationError[] }
    saveErrors.value = problem.errors ?? []
    saveMessage.value = messageOf(error, '試算できませんでした')
    return
  }
  dryRun.value = data
}

function verdictLabel(verdict: string): string {
  return (
    { PASS: '合格', PASS_WITH_WARNINGS: '合格（警告あり）', FAIL: '不合格' }[verdict] ?? verdict
  )
}

function sourceLabel(sourceType: string): string {
  if (sourceType === 'FILE') return '.quality-gate.yml'
  if (sourceType === 'UI') return '画面から設定'
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
        <button type="button" role="tab" :aria-selected="tab === 'edit'" @click="tab = 'edit'">
          画面から編集
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

      <div v-else role="tabpanel">
        <!--
          ファイルが優先されることを明示し、ファイル管理のリポジトリでは編集させない。
          反映されない変更をして混乱する事故を防ぐ（docs/initial/08-screen-design.md 4.6）。
        -->
        <p class="qg-panel">
          CI が <code>.quality-gate.yml</code> を送った Run
          では、そのファイルが画面の設定より優先されます。
          収集ランナーはファイルを送らないため、画面の設定で判定します。
          <template v-if="!config.editable">
            直近の Run がファイルの設定で判定されているため、画面からは編集できません。
          </template>
        </p>
        <p v-if="!auth.isAdmin" class="qg-muted">この操作には管理者権限が必要です。</p>

        <form class="qg-form" @submit.prevent="save">
          <label>
            設定（YAML）
            <textarea
              v-model="draft"
              rows="24"
              spellcheck="false"
              :disabled="!config.editable || !auth.isAdmin"
              :aria-invalid="saveErrors.length > 0 ? 'true' : 'false'"
              aria-describedby="config-save-status"
            />
          </label>
          <div id="config-save-status" aria-live="polite">
            <p v-if="saveMessage" :class="saveErrors.length > 0 ? 'qg-form__error' : 'qg-muted'">
              {{ saveMessage }}
            </p>
            <ul v-if="saveErrors.length > 0" class="qg-form__error">
              <li v-for="error in saveErrors" :key="`${error.line}/${error.message}`">
                <template v-if="error.line">{{ error.line }} 行目 </template>{{ error.path }}:
                {{ error.message }}
              </li>
            </ul>
          </div>
          <div class="qg-form__actions">
            <button
              type="submit"
              class="qg-button qg-button--primary"
              :disabled="!config.editable || !auth.isAdmin || saving"
            >
              検証して保存
            </button>
            <button
              type="button"
              class="qg-button"
              :disabled="!auth.isAdmin || dryRunning"
              @click="simulate"
            >
              過去の Run で試算（保存しない）
            </button>
          </div>
        </form>

        <section v-if="dryRun" class="qg-dry-run" aria-labelledby="dry-run-title">
          <h2 id="dry-run-title">試算の結果（保存していません）</h2>
          <p role="status">
            既定ブランチの直近 {{ dryRun.evaluated }} 件の Run のうち、判定が変わるのは
            {{ dryRun.verdictChanged }} 件です（不合格になる
            {{ dryRun.newlyFailing }} 件、合格になる {{ dryRun.newlyPassing }} 件）。
          </p>
          <table v-if="dryRun.runs.length > 0">
            <caption class="qg-visually-hidden">
              Run ごとの現在の判定と、この設定での判定
            </caption>
            <thead>
              <tr>
                <th scope="col">計測日時</th>
                <th scope="col">コミット</th>
                <th scope="col">現在の判定</th>
                <th scope="col">この設定での判定</th>
                <th scope="col">状態が変わる指標</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="run in dryRun.runs" :key="run.runId">
                <td>{{ formatDateTime(run.measuredAt) }}</td>
                <td>
                  <RouterLink :to="{ name: 'run', params: { runId: run.runId } }">
                    {{ shortSha(run.commitSha) }}
                  </RouterLink>
                </td>
                <td>
                  <StatusChip :status="verdictToStatus(run.currentVerdict as Verdict)" />
                  {{ verdictLabel(run.currentVerdict) }}
                </td>
                <td>
                  <StatusChip :status="verdictToStatus(run.simulatedVerdict as Verdict)" />
                  {{ verdictLabel(run.simulatedVerdict) }}
                </td>
                <td>
                  <span v-if="run.changes.length === 0" class="qg-muted">なし</span>
                  <ul v-else>
                    <li
                      v-for="change in run.changes"
                      :key="`${change.metricId}|${change.componentName ?? ''}`"
                    >
                      {{ change.metricId
                      }}<template v-if="change.componentName">
                        （{{ change.componentName }}）</template
                      >:
                      <StatusChip
                        v-if="change.currentStatus"
                        :status="change.currentStatus as MeasurementStatus"
                      />
                      →
                      <StatusChip :status="change.simulatedStatus as MeasurementStatus" />
                      {{ formatValue(change.value, change.unit) }}
                    </li>
                  </ul>
                </td>
              </tr>
            </tbody>
          </table>
          <ul v-if="dryRun.skipped.length > 0" class="qg-muted">
            <li v-for="skip in dryRun.skipped" :key="skip.runId">
              {{ shortSha(skip.runId) }}: {{ skip.reason }}
            </li>
          </ul>
        </section>
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
.qg-dry-run {
  margin-top: 1.5rem;
}
.qg-dry-run table {
  border-collapse: collapse;
}
.qg-dry-run th,
.qg-dry-run td {
  padding: 0.3rem 0.75rem;
  border-bottom: 1px solid var(--border);
  text-align: left;
  vertical-align: top;
}
.qg-dry-run ul {
  margin: 0;
  padding-left: 1rem;
}
textarea {
  font-family: var(--font-mono, ui-monospace, monospace);
}
</style>
