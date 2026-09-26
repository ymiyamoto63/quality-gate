<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import StatusChip from '@/components/StatusChip.vue'
import MetricRow from '@/components/MetricRow.vue'
import { useRunStore } from '@/stores/run'
import { useAuthStore } from '@/stores/auth'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'
import { verdictToStatus } from '@/api/status'
import { formatDateTime, shortSha } from '@/api/format'

const route = useRoute()
const store = useRunStore()
const auth = useAuthStore()

const runId = computed(() => String(route.params.runId))
const detail = computed(() => store.detail)

/**
 * どのカテゴリを開いているか。初期状態はサーバの expandByDefault に従う。
 * 利用者が開閉したあとは、その操作を優先する。
 */
const expanded = ref<Record<string, boolean>>({})

watch(
  detail,
  (value) => {
    if (!value) return
    expanded.value = Object.fromEntries(
      value.categories.map((c) => [c.category, c.expandByDefault]),
    )
  },
  { immediate: true },
)

onMounted(() => store.load(runId.value))
watch(runId, (id) => store.load(id))

function toggle(category: string): void {
  expanded.value = { ...expanded.value, [category]: !expanded.value[category] }
}

const reevaluateMessage = ref('')
const reevaluating = ref(false)

/**
 * 再評価はその場で判定し直す（D-27）。終わったら表示を読み直す。
 * 権限の無い利用者にもボタンは見せ、無効化して理由を示す（docs/08 5 章）。
 */
async function reevaluate(): Promise<void> {
  reevaluating.value = true
  reevaluateMessage.value = '再評価しています…'
  const { data, error } = await api.POST('/api/v1/runs/{runId}/reevaluate', {
    params: { path: { runId: runId.value } },
  })
  reevaluating.value = false
  if (error || !data) {
    reevaluateMessage.value = messageOf(error, '再評価できませんでした')
    return
  }
  reevaluateMessage.value =
    data.status === 'FAILED'
      ? '再評価しましたが、処理に失敗しました。理由を確認してください。'
      : '再評価しました。'
  await store.load(runId.value)
}

const artifacts = ref<components['schemas']['ArtifactItem'][] | null>(null)

async function loadArtifacts(): Promise<void> {
  const { data } = await api.GET('/api/v1/runs/{runId}/artifacts', {
    params: { path: { runId: runId.value } },
  })
  artifacts.value = data?.items ?? []
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <section>
    <p v-if="store.state === 'loading'" class="qg-muted">読み込み中…</p>

    <p v-else-if="store.state === 'error'" role="alert">
      {{ store.errorMessage }}
      <button type="button" @click="store.load(runId)">再試行</button>
    </p>

    <template v-else-if="detail">
      <header class="qg-run-head">
        <div class="qg-run-head__title">
          <h1>Run {{ shortSha(detail.commitSha) }}</h1>
          <StatusChip :status="verdictToStatus(detail.verdict)" />
          <!--
            部分計測は判定と並べて常に示す。「測った範囲では合格」を
            「合格」とだけ表示すると、計測が欠けていることが見えなくなる。
          -->
          <span v-if="detail.completeness === 'PARTIAL'" class="qg-badge">
            部分計測（未計測 {{ detail.skippedMetrics.length }} 件）
          </span>
          <button
            v-if="detail.status === 'EVALUATED' || detail.status === 'FAILED'"
            type="button"
            class="qg-button qg-reevaluate"
            :disabled="!auth.isAdmin || reevaluating"
            :aria-describedby="auth.isAdmin ? undefined : 'reevaluate-note'"
            @click="reevaluate"
          >
            再評価
          </button>
        </div>
        <p v-if="!auth.isAdmin" id="reevaluate-note" class="qg-visually-hidden">
          再評価には管理者権限が必要です
        </p>
        <p class="qg-muted" aria-live="polite">{{ reevaluateMessage }}</p>

        <p class="qg-muted">
          {{ detail.branch }} · {{ formatDateTime(detail.measuredAt) }}
          <span v-if="detail.attempt > 1"> · 試行 {{ detail.attempt }} 回目</span>
        </p>

        <p class="qg-links">
          <a v-if="detail.commitUrl" :href="detail.commitUrl" target="_blank" rel="noopener">
            コミット {{ shortSha(detail.commitSha) }}
            <span class="qg-visually-hidden">（外部サイト）</span>
            <i class="pi pi-external-link" aria-hidden="true" />
          </a>
          <a v-if="detail.ciRunUrl" :href="detail.ciRunUrl" target="_blank" rel="noopener">
            CI 実行 <span class="qg-visually-hidden">（外部サイト）</span>
            <i class="pi pi-external-link" aria-hidden="true" />
          </a>
          <span v-if="detail.gateConfig">
            設定 v{{ detail.gateConfig.version }}（{{
              detail.gateConfig.sourceType === 'FILE' ? '設定ファイル' : '画面から設定'
            }}）
          </span>
          <span v-else>設定 既定値</span>
        </p>

        <p v-if="detail.baselineRunId" class="qg-muted">
          比較対象:
          <RouterLink :to="{ name: 'run', params: { runId: detail.baselineRunId } }">
            Run {{ shortSha(detail.baseCommitSha) }}
          </RouterLink>
        </p>
        <p v-else class="qg-muted">
          比較対象がないため、既存の違反は「新規」ではなく「初回」として数えています。
        </p>
      </header>

      <!--
        処理失敗（FAILED）は判定結果 FAIL とは別物なので、判定表を出さずに
        何が起きたかと次の行動を示す（docs/initial/08-screen-design.md 3.3）。
      -->
      <div v-if="detail.failure" class="qg-failure" role="alert">
        <h2>{{ detail.failure.title }}</h2>
        <p class="qg-code">{{ detail.failure.errorCode }}</p>
        <pre v-if="detail.failure.detail">{{ detail.failure.detail }}</pre>
        <p>{{ detail.failure.hint }}</p>
      </div>

      <p v-else-if="detail.categories.length === 0" class="qg-empty">
        判定結果がまだありません。成果物の取り込みと判定が終わると表示されます。
      </p>

      <ul v-else class="qg-categories">
        <li v-for="category in detail.categories" :key="category.category" class="qg-category">
          <h2>
            <button
              type="button"
              class="qg-category__toggle"
              :aria-expanded="expanded[category.category] ? 'true' : 'false'"
              @click="toggle(category.category)"
            >
              <i
                class="pi"
                :class="expanded[category.category] ? 'pi-chevron-down' : 'pi-chevron-right'"
                aria-hidden="true"
              />
              <span class="qg-category__name">{{ category.category }}</span>
              <StatusChip :status="category.status" />
            </button>
          </h2>

          <div v-show="expanded[category.category]">
            <MetricRow
              v-for="metric in category.metrics"
              :key="`${metric.metricId}/${metric.componentName ?? ''}`"
              :metric="metric"
              :run-id="detail.runId"
            />
          </div>
        </li>
      </ul>

      <!--
        スキップは「申告あり / 申告なし」を併記する。受理されなかった申告は
        指標が ERROR になった理由そのものなので、隠すと原因が追えない。
      -->
      <section v-if="detail.skippedMetrics.length > 0" class="qg-section">
        <h2>未計測の指標</h2>
        <ul class="qg-skips">
          <li v-for="skip in detail.skippedMetrics" :key="skip.metricId">
            <strong>{{ skip.name }}</strong>
            <span :class="skip.accepted ? 'qg-muted' : 'qg-warning'">
              {{ skip.accepted ? '（申告あり・受理）' : '（申告あり・未許容のため計測エラー）' }}
            </span>
            <br />
            <span class="qg-muted">{{ skip.reason }}</span>
          </li>
        </ul>
      </section>

      <footer v-if="!detail.failure" class="qg-section qg-muted">
        違反の内訳: 新規 {{ detail.findingSummary.newCount }} ・ 継続
        {{ detail.findingSummary.continuing }} ・ 解消 {{ detail.findingSummary.resolved }} ・ 初回
        {{ detail.findingSummary.initial }}
        <br />
        取り込んだ成果物: {{ detail.artifactCount }} 件
        <button
          v-if="artifacts === null"
          type="button"
          class="qg-link-button"
          @click="loadArtifacts"
        >
          一覧
        </button>
        <ul v-if="artifacts" class="qg-artifacts">
          <li v-for="artifact in artifacts" :key="artifact.artifactId">
            <a
              v-if="!artifact.deleted"
              :href="`/api/v1/runs/${detail.runId}/artifacts/${artifact.artifactId}/content`"
              download
            >
              {{ artifact.filename }}
            </a>
            <span v-else>{{ artifact.filename }}（保持期間を過ぎて削除済み）</span>
            <span>
              · {{ artifact.type }}{{ artifact.component ? ` · ${artifact.component}` : '' }} ·
              {{ formatBytes(artifact.sizeBytes) }}</span
            >
          </li>
        </ul>
        <br />
        <RouterLink :to="{ name: 'findings', params: { runId: detail.runId } }">
          違反一覧を見る →
        </RouterLink>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.qg-reevaluate {
  margin-left: auto;
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
.qg-artifacts {
  margin: 0.25rem 0;
}
.qg-run-head__title {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.qg-run-head h1 {
  margin: 0;
  font-size: 1.25rem;
}

.qg-badge {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.1rem 0.5rem;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.qg-links {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
  font-size: 0.875rem;
}

.qg-categories {
  list-style: none;
  padding: 0;
  margin: 1rem 0 0;
}

.qg-category {
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.5rem 1rem 0.75rem;
  margin-bottom: 0.75rem;
}

.qg-category h2 {
  margin: 0;
  font-size: 1rem;
}

.qg-category__toggle {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  background: none;
  border: none;
  padding: 0.5rem 0;
  font: inherit;
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.qg-category__name {
  flex: 1;
}

.qg-failure {
  background: var(--surface-1);
  border: 1px solid var(--status-fail);
  border-radius: var(--radius);
  padding: 1rem;
  margin-top: 1rem;
}

.qg-failure h2 {
  margin-top: 0;
  font-size: 1rem;
}

.qg-failure pre {
  white-space: pre-wrap;
  font-size: 0.875rem;
  background: var(--surface-page);
  padding: 0.75rem;
  border-radius: var(--radius);
}

.qg-code {
  font-family: ui-monospace, monospace;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.qg-section {
  margin-top: 1.5rem;
}

.qg-skips {
  list-style: none;
  padding: 0;
  line-height: 1.7;
}

.qg-warning {
  color: var(--status-fail);
}

.qg-muted {
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.qg-empty {
  color: var(--text-secondary);
}

.qg-visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
</style>
