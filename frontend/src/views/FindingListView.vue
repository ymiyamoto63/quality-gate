<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  useFindingsStore,
  defaultFilter,
  type FindingState,
  type Severity,
  type FindingItem,
} from '@/stores/findings'
import { shortSha } from '@/api/format'

const route = useRoute()
const router = useRouter()
const store = useFindingsStore()

const runId = computed(() => String(route.params.runId))

const STATE_LABELS: Record<FindingState, string> = {
  NEW: '新規',
  CONTINUING: '継続',
  RESOLVED: '解消',
  INITIAL: '初回',
}

const SEVERITY_LABELS: Record<Severity, string> = {
  CRITICAL: '重大',
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
  INFO: '情報',
}

const ALL_STATES = Object.keys(STATE_LABELS) as FindingState[]
const ALL_SEVERITIES = Object.keys(SEVERITY_LABELS) as Severity[]

onMounted(() => {
  // 指標行の「違反 N 件を見る」から来たときは、その指標に絞った状態で開く
  const metricId = route.query.metricId
  store.filter = {
    ...defaultFilter(),
    metricId: typeof metricId === 'string' ? [metricId] : [],
  }
  void store.load(runId.value)
})

watch(runId, (id) => store.load(id))

function toggleState(value: FindingState): void {
  const next = store.filter.state.includes(value)
    ? store.filter.state.filter((s) => s !== value)
    : [...store.filter.state, value]
  void store.setFilter(runId.value, { ...store.filter, state: next })
}

function toggleSeverity(value: Severity): void {
  const next = store.filter.severity.includes(value)
    ? store.filter.severity.filter((s) => s !== value)
    : [...store.filter.severity, value]
  void store.setFilter(runId.value, { ...store.filter, severity: next })
}

function clearMetricFilter(): void {
  void router.replace({ name: 'findings', params: { runId: runId.value } })
  void store.setFilter(runId.value, { ...store.filter, metricId: [] })
}

/** 依存パッケージの脆弱性など、行番号を持たない違反がある。 */
function location(item: FindingItem): string | null {
  if (!item.filePath) return null
  return item.line === null ? item.filePath : `${item.filePath}:${item.line}`
}

function detailLine(item: FindingItem): string | null {
  const parts: string[] = []
  const pkg = item.detail.package
  const installed = item.detail.installedVersion
  const fixed = item.detail.fixedVersion
  if (typeof pkg === 'string') {
    parts.push(
      typeof installed === 'string' && typeof fixed === 'string'
        ? `${pkg} ${installed} → ${fixed}`
        : pkg,
    )
  }
  if (typeof item.detail.cvssScore === 'number') parts.push(`CVSS ${item.detail.cvssScore}`)
  if (typeof item.detail.complexity === 'number') parts.push(`複雑度 ${item.detail.complexity}`)
  // アクセシビリティ違反はファイルではなく画面の要素で位置を示す
  if (typeof item.detail.page === 'string') parts.push(`画面 ${item.detail.page}`)
  if (typeof item.detail.selector === 'string') parts.push(`要素 ${item.detail.selector}`)
  if (typeof item.detail.impact === 'string') parts.push(`axe impact: ${item.detail.impact}`)
  return parts.length > 0 ? parts.join(' · ') : null
}
</script>

<template>
  <section>
    <h1>
      違反一覧 —
      <RouterLink :to="{ name: 'run', params: { runId } }">Run {{ shortSha(runId) }}</RouterLink>
    </h1>

    <fieldset class="qg-filters">
      <legend class="qg-visually-hidden">絞り込み</legend>

      <div class="qg-filter-group" role="group" aria-label="状態">
        <span class="qg-filter-label">状態</span>
        <label v-for="value in ALL_STATES" :key="value" class="qg-chip">
          <input
            type="checkbox"
            :checked="store.filter.state.includes(value)"
            @change="toggleState(value)"
          />
          {{ STATE_LABELS[value] }}
        </label>
      </div>

      <div class="qg-filter-group" role="group" aria-label="深刻度">
        <span class="qg-filter-label">深刻度</span>
        <label v-for="value in ALL_SEVERITIES" :key="value" class="qg-chip">
          <input
            type="checkbox"
            :checked="store.filter.severity.includes(value)"
            @change="toggleSeverity(value)"
          />
          {{ SEVERITY_LABELS[value] }}
        </label>
      </div>

      <p v-if="store.filter.metricId.length > 0" class="qg-filter-group">
        指標 {{ store.filter.metricId.join(', ') }} に絞り込み中
        <button type="button" @click="clearMetricFilter">解除</button>
      </p>
    </fieldset>

    <p v-if="store.state === 'loading'" class="qg-muted">読み込み中…</p>

    <p v-else-if="store.state === 'error'" role="alert">
      {{ store.errorMessage }}
      <button type="button" @click="store.load(runId)">再試行</button>
    </p>

    <p v-else-if="store.items.length === 0" class="qg-empty">
      この条件に一致する違反はありません。絞り込みを緩めると、解消済みの違反も表示できます。
    </p>

    <template v-else>
      <p class="qg-muted" role="status">
        {{ store.totalCount }} 件中 {{ store.items.length }} 件を表示しています
      </p>

      <ul class="qg-findings">
        <li v-for="item in store.items" :key="item.findingId" class="qg-finding">
          <div class="qg-finding__head">
            <span class="qg-tag" :data-state="item.state">{{ STATE_LABELS[item.state] }}</span>
            <span class="qg-tag" :data-severity="item.severity">
              {{ SEVERITY_LABELS[item.severity] }}
            </span>
            <span class="qg-finding__title">{{ item.title }}</span>
          </div>

          <p class="qg-muted">
            <span v-if="location(item)">{{ location(item) }}</span>
            <span v-if="location(item) && detailLine(item)"> · </span>
            <span v-if="detailLine(item)">{{ detailLine(item) }}</span>
          </p>

          <p class="qg-finding__links">
            <a v-if="item.sourceUrl" :href="item.sourceUrl" target="_blank" rel="noopener">
              GitHub で見る <span class="qg-visually-hidden">（外部サイト）</span>
              <i class="pi pi-external-link" aria-hidden="true" />
            </a>
            <a
              v-if="typeof item.detail.advisoryUrl === 'string'"
              :href="String(item.detail.advisoryUrl)"
              target="_blank"
              rel="noopener"
            >
              アドバイザリ <span class="qg-visually-hidden">（外部サイト）</span>
              <i class="pi pi-external-link" aria-hidden="true" />
            </a>
            <a
              v-if="typeof item.detail.helpUrl === 'string'"
              :href="String(item.detail.helpUrl)"
              target="_blank"
              rel="noopener"
            >
              ルールの解説 <span class="qg-visually-hidden">（外部サイト）</span>
              <i class="pi pi-external-link" aria-hidden="true" />
            </a>
          </p>

          <!--
            免除中の違反を薄く表示しない。免除は解決ではないため、期限が切れて
            不合格に戻ったときに「急に問題が増えた」と受け取られる。
          -->
          <p v-if="item.waiver" class="qg-waiver">
            免除中（期限 {{ item.waiver.expiresOn }} · 理由: {{ item.waiver.reason }}）
          </p>
        </li>
      </ul>

      <button v-if="store.nextCursor" type="button" @click="store.loadMore(runId)">
        さらに読み込む
      </button>
    </template>
  </section>
</template>

<style scoped>
.qg-filters {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.75rem 1rem;
  margin-bottom: 1rem;
}

.qg-filter-group {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin: 0.35rem 0;
}

.qg-filter-label {
  font-size: 0.875rem;
  color: var(--text-secondary);
  min-width: 4rem;
}

.qg-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.875rem;
}

.qg-findings {
  list-style: none;
  padding: 0;
}

.qg-finding {
  /* CSS セレクタや長いパスで横にはみ出さない */
  overflow-wrap: anywhere;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.75rem 1rem;
  margin-bottom: 0.5rem;
}

.qg-finding__head {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.qg-finding__title {
  font-weight: 600;
}

.qg-finding__links {
  display: flex;
  gap: 1rem;
  font-size: 0.875rem;
  margin: 0.25rem 0 0;
}

/* 分類は文字で示し、色は補助に留める（強制カラーモードでも意味が残る） */
.qg-tag {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.05rem 0.4rem;
  font-size: 0.75rem;
  white-space: nowrap;
}

.qg-tag[data-severity='CRITICAL'],
.qg-tag[data-severity='HIGH'] {
  border-color: var(--status-fail);
}

.qg-tag[data-state='RESOLVED'] {
  border-color: var(--status-pass);
}

.qg-waiver {
  margin: 0.35rem 0 0;
  font-size: 0.875rem;
  border-left: 3px solid var(--status-warn);
  padding-left: 0.5rem;
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
