<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { components } from '@/api/schema'
import StatusChip from '@/components/StatusChip.vue'
import { formatDateTime, formatDelta, formatValue, shortSha } from '@/api/format'
import { verdictToStatus, type MeasurementStatus, type Verdict } from '@/api/status'

type Report = components['schemas']['ReportResponse']

/**
 * 品質レポート（S-10。FR-08-4）。
 *
 * 期間内の既定ブランチの判定をリポジトリごとにまとめる。明細は CSV で取り出し、
 * PDF はブラウザの印刷（「PDF として保存」）で出す。印刷では操作部品を隠す（下の @media print）。
 */
const today = new Date()
const from = ref(isoDate(new Date(today.getTime() - 29 * 86_400_000)))
const to = ref(isoDate(today))
const report = ref<Report | null>(null)
const state = ref<'idle' | 'loading' | 'error'>('idle')
const errorMessage = ref('')
/** 表示と CSV の対象。空なら全リポジトリ。 */
const selected = ref<string[]>([])

const shown = computed(() => {
  const all = report.value?.repositories ?? []
  return selected.value.length === 0
    ? all
    : all.filter((r) => selected.value.includes(r.repositoryId))
})

const csvUrl = computed(() => {
  const params = new URLSearchParams({ from: from.value, to: to.value })
  for (const id of selected.value) params.append('repositoryId', id)
  return `/api/v1/reports/measurements.csv?${params.toString()}`
})

async function load(): Promise<void> {
  state.value = 'loading'
  const { data, error } = await api.GET('/api/v1/reports', {
    params: { query: { from: from.value, to: to.value } },
  })
  if (error || !data) {
    state.value = 'error'
    errorMessage.value =
      (error as { detail?: string } | undefined)?.detail ?? 'レポートを取得できませんでした'
    return
  }
  report.value = data
  selected.value = selected.value.filter((id) =>
    data.repositories.some((r) => r.repositoryId === id),
  )
  state.value = 'idle'
}

function print(): void {
  window.print()
}

function isoDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function verdictLabel(verdict: string): string {
  return (
    { PASS: '合格', PASS_WITH_WARNINGS: '合格（警告あり）', FAIL: '不合格' }[verdict] ?? verdict
  )
}

onMounted(load)
</script>

<template>
  <section class="qg-report">
    <h1>品質レポート</h1>

    <form class="qg-filters qg-no-print" @submit.prevent="load">
      <label>
        開始日
        <input v-model="from" type="date" required />
      </label>
      <label>
        終了日
        <input v-model="to" type="date" required />
      </label>
      <button type="submit">表示</button>
      <a class="qg-button" :href="csvUrl" download>明細を CSV でダウンロード</a>
      <button type="button" :disabled="!report" @click="print">PDF として保存（印刷）</button>
    </form>

    <fieldset v-if="report && report.repositories.length > 1" class="qg-repos qg-no-print">
      <legend>対象のリポジトリ（選ばなければすべて）</legend>
      <label v-for="r in report.repositories" :key="r.repositoryId">
        <input v-model="selected" type="checkbox" :value="r.repositoryId" />
        {{ r.fullName }}
      </label>
    </fieldset>

    <p v-if="state === 'loading'" class="qg-muted">読み込み中…</p>
    <p v-else-if="state === 'error'" role="alert">
      {{ errorMessage }}
      <button type="button" @click="load">再試行</button>
    </p>

    <template v-else-if="report">
      <p class="qg-muted">
        期間: {{ report.from }} 〜 {{ report.to }}（{{
          report.zone
        }}）。対象は既定ブランチで判定された Run です。
      </p>
      <p v-if="shown.length === 0" class="qg-muted">対象のリポジトリがありません。</p>

      <article v-for="r in shown" :key="r.repositoryId" class="qg-report__repo">
        <h2>{{ r.fullName }}（{{ r.defaultBranch }}）</h2>
        <dl class="qg-report__summary">
          <div>
            <dt>判定された Run</dt>
            <dd>{{ r.runs }} 件</dd>
          </div>
          <div>
            <dt>合格率</dt>
            <dd>{{ r.passRate === null ? '—' : `${r.passRate}%` }}</dd>
          </div>
          <div>
            <dt>内訳</dt>
            <dd>
              合格 {{ r.passed }} ／ 警告あり {{ r.passedWithWarnings }} ／ 不合格 {{ r.failed }}
            </dd>
          </div>
          <div v-if="r.latest">
            <dt>最新の判定</dt>
            <dd>
              <StatusChip :status="verdictToStatus(r.latest.verdict as Verdict)" />
              {{ verdictLabel(r.latest.verdict) }}
              <RouterLink :to="{ name: 'run', params: { runId: r.latest.runId } }">
                {{ shortSha(r.latest.commitSha) }}
              </RouterLink>
              （{{ formatDateTime(r.latest.measuredAt) }}）
            </dd>
          </div>
        </dl>

        <table v-if="r.metrics.length > 0">
          <caption>
            {{
              r.fullName
            }}
            の最新の指標と期間内の変化
          </caption>
          <thead>
            <tr>
              <th scope="col">指標</th>
              <th scope="col">コンポーネント</th>
              <th scope="col">判定</th>
              <th scope="col">最新の値</th>
              <th scope="col">期間の最初の値</th>
              <th scope="col">変化</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="m in r.metrics"
              :key="`${m.metricId}|${m.componentName ?? ''}|${m.variant ?? ''}`"
            >
              <th scope="row">
                {{ m.name }}<span v-if="m.variant" class="qg-muted">（{{ m.variant }}）</span>
              </th>
              <td>{{ m.componentName ?? '—' }}</td>
              <td><StatusChip :status="m.status as MeasurementStatus" /></td>
              <td>{{ formatValue(m.value, m.unit) }}</td>
              <td>{{ formatValue(m.firstValue, m.unit) }}</td>
              <td>{{ formatDelta(m.change, m.unit) ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="qg-muted">期間内に判定された Run がありません。</p>
      </article>
    </template>
  </section>
</template>

<style scoped>
.qg-filters {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 1rem;
}
.qg-filters label {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.875rem;
}
.qg-filters input,
.qg-filters button,
.qg-button {
  font: inherit;
  padding: 0.25rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-1);
  color: var(--text-primary);
  text-decoration: none;
}
.qg-repos {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  margin-bottom: 1rem;
}
.qg-report__repo {
  margin-top: 1.5rem;
  break-inside: avoid;
}
.qg-report__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;
}
.qg-report__summary dt {
  font-size: 0.8rem;
  color: var(--text-secondary);
}
.qg-report__summary dd {
  margin: 0;
}
table {
  border-collapse: collapse;
  margin-top: 0.75rem;
}
th,
td {
  padding: 0.3rem 0.75rem;
  border-bottom: 1px solid var(--border);
  text-align: left;
}
caption {
  text-align: left;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

@media print {
  .qg-no-print {
    display: none;
  }
}
</style>
