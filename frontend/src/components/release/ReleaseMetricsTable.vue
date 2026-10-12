<script setup lang="ts">
import { computed } from 'vue'
import StatusChip from '@/components/StatusChip.vue'
import type { components } from '@/api/schema'
import { formatValue, relaxedLabel } from '@/api/format'

type Row = components['schemas']['ReleaseMetric']
type Guide = components['schemas']['ReleaseGuide']

/**
 * 品質基準ごとの合否の表。並び（問題のある指標が先）はサーバが決める。
 *
 * 「何を見るか」は指標の説明（guides）の summary を指標 ID で引いて出す。
 * 不合格・計測エラーの行には理由と主な違反を添える。行が無ければ何も出さない。
 */
const props = defineProps<{ metrics: Row[]; guides: Guide[] }>()

const summaries = computed(() => new Map(props.guides.map((g) => [g.metricId, g.summary])))

function qualifier(row: Row): string {
  const parts = [row.componentName, row.variantLabel].filter(Boolean)
  return parts.length > 0 ? `（${parts.join('・')}）` : ''
}

function rowKey(row: Row): string {
  return `${row.metricId}|${row.componentName ?? ''}|${row.variantLabel ?? ''}`
}
</script>

<template>
  <section v-if="metrics.length > 0" aria-labelledby="metrics-heading">
    <h2 id="metrics-heading">品質基準ごとの合否</h2>
    <p class="qg-muted">
      合格ラインを満たさない指標と、計測できなかった指標（計測エラー）はリリース不可の理由になります。問題のある指標を先に並べています。
    </p>
    <table class="qg-table qg-table--stack">
      <thead>
        <tr>
          <th scope="col">判定</th>
          <th scope="col">指標</th>
          <th scope="col">何を見るか</th>
          <th scope="col">合格ライン</th>
          <th scope="col">結果</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in metrics" :key="rowKey(row)" :data-status="row.status">
          <td data-label="判定"><StatusChip :status="row.status" /></td>
          <th scope="row" data-label="指標">
            {{ row.name }}<span class="qg-muted">{{ qualifier(row) }}</span>
          </th>
          <td data-label="何を見るか">{{ summaries.get(row.metricId) }}</td>
          <td data-label="合格ライン">
            {{ row.threshold ?? '—' }}
            <p v-if="row.thresholdRelaxed" class="qg-release__loosened">
              <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>
              {{ relaxedLabel(row.defaultThreshold) }}
            </p>
            <p v-if="row.excludedFileCount > 0" class="qg-release__loosened">
              <i class="pi pi-filter" aria-hidden="true"></i>
              {{ row.excludedFileCount }} ファイルを計測から除外（QG_EXCLUSIONS）
            </p>
          </td>
          <td data-label="結果">
            {{ formatValue(row.value, row.unit) }}
            <p v-if="row.status !== 'PASS' && row.reason" class="qg-release__why">
              {{ row.reason }}
            </p>
            <details v-if="row.findings.length > 0" class="qg-release__findings">
              <summary>主な違反（{{ row.findingCount }} 件）</summary>
              <ul>
                <li v-for="(finding, index) in row.findings" :key="index">
                  {{ finding.title }}
                  <template v-if="finding.location">
                    —
                    <a v-if="finding.url" :href="finding.url" rel="noopener" target="_blank">
                      <code>{{ finding.location }}</code>
                    </a>
                    <code v-else>{{ finding.location }}</code>
                  </template>
                </li>
              </ul>
              <p v-if="row.findingCount > row.findings.length" class="qg-muted">
                ほか
                {{ row.findingCount - row.findings.length }}
                件。すべての違反は計測のログ（計測日時のリンク）で確かめられます。
              </p>
            </details>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
h2 {
  font-size: 1.0625rem;
  margin-top: 2rem;
}
/* 緩めた合格ライン・除外の印。色ではなく記号と文言で示す（文字色は本文のまま） */
.qg-release__loosened {
  display: flex;
  align-items: baseline;
  gap: 0.35rem;
  margin: 0.25rem 0 0;
  font-size: 0.875rem;
  font-weight: 600;
}
.qg-release__why {
  margin: 0.25rem 0 0;
  font-size: 0.875rem;
}
.qg-release__findings {
  margin-top: 0.35rem;
  font-size: 0.875rem;
}
.qg-release__findings summary {
  cursor: pointer;
}
.qg-release__findings ul {
  margin: 0.35rem 0;
  padding-left: 1.25rem;
}
.qg-release__findings code {
  word-break: break-all;
}
</style>
