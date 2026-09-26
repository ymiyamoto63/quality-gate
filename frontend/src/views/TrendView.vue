<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import TrendChart from '@/components/TrendChart.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useTrendStore, RANGES } from '@/stores/trend'
import { formatValue, formatThreshold, formatDateTime, shortSha } from '@/api/format'
import type { MeasurementStatus } from '@/api/status'

const route = useRoute()
const store = useTrendStore()

const repositoryId = computed(() => String(route.params.repositoryId))
const trend = computed(() => store.trend)

/**
 * 選べる指標は判定器が実装済みのものに限る。
 * 未実装の指標を並べると、選んでも必ず空のグラフになる。
 */
const METRICS = [
  { metricId: 'M-01', name: 'ブランチカバレッジ' },
  { metricId: 'M-02', name: 'ミューテーションスコア' },
  { metricId: 'M-06', name: '重大・高 脆弱性件数' },
  { metricId: 'M-07', name: '循環的複雑度 15 超の新規関数数' },
  { metricId: 'M-08', name: '破壊的変更件数' },
  { metricId: 'M-09', name: 'アクセシビリティ違反' },
  { metricId: 'M-10', name: 'テスト成功率' },
  { metricId: 'M-11', name: 'スキップされたテスト数' },
  { metricId: 'M-12', name: 'シークレット検出件数' },
  { metricId: 'M-13', name: 'ライセンス違反件数' },
]

onMounted(() => store.load(repositoryId.value))
watch([repositoryId, () => store.metricId, () => store.days], () => store.load(repositoryId.value))

const thresholdValue = computed(() => {
  const value = trend.value?.threshold?.value
  return typeof value === 'number' ? value : null
})

const thresholdLabel = computed(() => {
  const text = formatThreshold(trend.value?.threshold ?? null, trend.value?.unit ?? null)
  return text === null ? null : `合格ライン ${text}`
})

/** 図の説明。何本の線が何を示しているかを文で持たせる。 */
const caption = computed(() => {
  if (!trend.value) return ''
  const names = trend.value.series.map((s) => s.label).join('・')
  const range = `${formatDateTime(trend.value.from)} から ${formatDateTime(trend.value.to)}`
  if (trend.value.series.length === 0) {
    return `${trend.value.name} の推移（${range}）`
  }
  return `${trend.value.name} の推移（${range}、系列: ${names}）`
})

/** 表は時刻ごとの行にする。系列をまたいで同じ Run の値を並べて読めるようにする。 */
const tableRows = computed(() => {
  if (!trend.value) return []
  const byTime = new Map<string, { runId: string; commitSha: string; cells: Map<string, Cell> }>()

  for (const series of trend.value.series) {
    for (const point of series.points) {
      const row = byTime.get(point.measuredAt) ?? {
        runId: point.runId,
        commitSha: point.commitSha,
        cells: new Map<string, Cell>(),
      }
      row.cells.set(series.seriesId, { value: point.value, status: point.status })
      byTime.set(point.measuredAt, row)
    }
  }

  return [...byTime.entries()]
    .sort(([a], [b]) => b.localeCompare(a))
    .map(([measuredAt, row]) => ({ measuredAt, ...row }))
})

interface Cell {
  value: number | null
  status: string
}

function cellOf(row: { cells: Map<string, Cell> }, seriesId: string): Cell | undefined {
  return row.cells.get(seriesId)
}
</script>

<template>
  <section>
    <h1>トレンド</h1>

    <div class="qg-filters">
      <label>
        指標
        <select v-model="store.metricId">
          <option v-for="metric in METRICS" :key="metric.metricId" :value="metric.metricId">
            {{ metric.name }}
          </option>
        </select>
      </label>
      <label>
        期間
        <select v-model.number="store.days">
          <option v-for="range in RANGES" :key="range.days" :value="range.days">
            {{ range.label }}
          </option>
        </select>
      </label>
      <span v-if="trend" class="qg-muted">ブランチ: {{ trend.branch }}</span>
    </div>

    <p v-if="store.state === 'loading'" class="qg-muted">読み込み中…</p>

    <p v-else-if="store.state === 'error'" role="alert">
      {{ store.errorMessage }}
      <button type="button" @click="store.load(repositoryId)">再試行</button>
    </p>

    <template v-else-if="trend">
      <!--
        しきい値は設定で変えられるため、期間内で一定とは限らない。
        線は現在の基準として 1 本引き、変化があったことは文で示す。
      -->
      <p v-if="trend.thresholdChanged" class="qg-notice" role="status">
        この期間内に合格ラインが変更されています。グラフの破線は現在の基準です。
        過去の点は当時の基準で判定されています。
      </p>

      <TrendChart
        :series="trend.series"
        :unit="trend.unit"
        :threshold-value="thresholdValue"
        :threshold-label="thresholdLabel"
        :caption="caption"
      />

      <!--
        表形式の代替表現。読み上げのためではなく（SVG は読み上げられる）、
        値をそのまま読みたい・コピーしたいという要求に応える。
      -->
      <details class="qg-table-toggle">
        <summary>表形式で見る（{{ tableRows.length }} 件）</summary>
        <table v-if="tableRows.length > 0">
          <caption class="qg-visually-hidden">
            {{
              caption
            }}
          </caption>
          <thead>
            <tr>
              <th scope="col">計測日時</th>
              <th scope="col">コミット</th>
              <th v-for="s in trend.series" :key="s.seriesId" scope="col">
                {{ s.label }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in tableRows" :key="row.measuredAt">
              <td>{{ formatDateTime(row.measuredAt) }}</td>
              <td>
                <RouterLink :to="{ name: 'run', params: { runId: row.runId } }">
                  {{ shortSha(row.commitSha) }}
                </RouterLink>
              </td>
              <td v-for="s in trend.series" :key="s.seriesId">
                <template v-if="cellOf(row, s.seriesId)">
                  {{ formatValue(cellOf(row, s.seriesId)!.value, trend.unit) }}
                  <StatusChip :status="cellOf(row, s.seriesId)!.status as MeasurementStatus" />
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </details>
    </template>
  </section>
</template>

<style scoped>
.qg-filters {
  display: flex;
  gap: 1.5rem;
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

.qg-filters select {
  font: inherit;
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-1);
  color: var(--text-primary);
}

.qg-notice {
  border: 1px solid var(--border);
  border-left: 3px solid var(--status-warn);
  border-radius: var(--radius);
  padding: 0.6rem 0.9rem;
  font-size: 0.875rem;
}

.qg-table-toggle {
  margin-top: 1.5rem;
}

.qg-table-toggle summary {
  cursor: pointer;
  font-size: 0.875rem;
}

table {
  border-collapse: collapse;
  margin-top: 0.75rem;
  font-size: 0.875rem;
}

th,
td {
  border-bottom: 1px solid var(--border);
  padding: 0.35rem 0.75rem;
  text-align: left;
  white-space: nowrap;
}

td {
  font-variant-numeric: tabular-nums;
}

.qg-muted {
  color: var(--text-secondary);
  font-size: 0.875rem;
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
