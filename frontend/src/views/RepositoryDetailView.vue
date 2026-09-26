<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import StatusChip from '@/components/StatusChip.vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'
import { verdictToStatus } from '@/api/status'
import { formatDateTime, formatDelta, formatThreshold, formatValue, shortSha } from '@/api/format'

type Schemas = components['schemas']
type LoadState = 'loading' | 'ready' | 'error'

const route = useRoute()
const repositoryId = computed(() => String(route.params.repositoryId))

const state = ref<LoadState>('loading')
const errorMessage = ref<string | null>(null)
const detail = ref<Schemas['RepositoryDetail'] | null>(null)
const latest = ref<Schemas['RunDetailResponse'] | null>(null)
const recentRuns = ref<Schemas['RunSummary'][]>([])

/**
 * 指標の表は最新 Run の Run 詳細から描く。判定表の組み立てを
 * リポジトリ詳細用に別に持つと、同じ指標が 2 つの画面で違って見える。
 */
const metrics = computed(() => latest.value?.categories.flatMap((c) => c.metrics) ?? [])

async function load(): Promise<void> {
  state.value = 'loading'
  detail.value = null
  latest.value = null
  const [repository, runs] = await Promise.all([
    api.GET('/api/v1/repositories/{repositoryId}', {
      params: { path: { repositoryId: repositoryId.value } },
    }),
    api.GET('/api/v1/runs', {
      params: { query: { repositoryId: repositoryId.value, limit: 10 } },
    }),
  ])
  if (repository.error || runs.error) {
    state.value = 'error'
    errorMessage.value = messageOf(
      repository.error ?? runs.error,
      'リポジトリを取得できませんでした',
    )
    return
  }
  detail.value = repository.data
  recentRuns.value = runs.data.items
  const runId = repository.data.latestRun?.runId
  if (runId) {
    const run = await api.GET('/api/v1/runs/{runId}', { params: { path: { runId } } })
    latest.value = run.data ?? null
  }
  document.title = `${repository.data.repository.fullName} | quality-gate`
  state.value = 'ready'
}

onMounted(load)
watch(repositoryId, load)

function metricLabel(metric: Schemas['RunMetric']): string {
  const qualifiers = [metric.componentName, metric.variantLabel].filter(Boolean)
  return qualifiers.length > 0 ? `${metric.name}（${qualifiers.join('・')}）` : metric.name
}
</script>

<template>
  <section>
    <p v-if="state === 'loading'" class="qg-muted">読み込み中…</p>

    <p v-else-if="state === 'error'" role="alert">
      {{ errorMessage }}
      <button type="button" class="qg-button" @click="load">再試行</button>
    </p>

    <template v-else-if="detail">
      <header class="qg-repo-head">
        <h1>{{ detail.repository.fullName }}</h1>
        <nav class="qg-repo-head__links" aria-label="このリポジトリの画面">
          <RouterLink :to="{ name: 'config', params: { repositoryId } }">設定</RouterLink>
          <RouterLink :to="{ name: 'trends', params: { repositoryId } }">トレンド</RouterLink>
          <RouterLink :to="{ name: 'release', params: { repositoryId } }">リリース判定</RouterLink>
        </nav>
      </header>

      <p v-if="!detail.repository.enabled" class="qg-panel" role="status">
        このリポジトリは無効化されています。計測結果は受け付けず、ダッシュボードにも表示されません。
      </p>

      <section class="qg-panel" aria-labelledby="latest-heading">
        <h2 id="latest-heading">最新の判定</h2>
        <p v-if="!detail.latestRun" class="qg-empty">
          まだ計測結果がありません。収集ランナー（または CI）から送信されると表示されます（Ingest
          Token は管理 › リポジトリで発行します）。
        </p>
        <template v-else>
          <p class="qg-latest">
            <StatusChip :status="verdictToStatus(detail.latestRun.verdict)" />
            <RouterLink :to="{ name: 'run', params: { runId: detail.latestRun.runId } }">
              {{ detail.latestRun.branch }} · {{ shortSha(detail.latestRun.commitSha) }} ·
              {{ formatDateTime(detail.latestRun.measuredAt) }}
            </RouterLink>
            <span v-if="detail.latestRun.completeness === 'PARTIAL'" class="qg-muted">
              · 部分計測
            </span>
          </p>
          <p class="qg-muted">
            最後の完全計測: {{ formatDateTime(detail.freshness.lastFullMeasuredAt) }}
            <RouterLink
              v-if="detail.lastFullRunId"
              :to="{ name: 'run', params: { runId: detail.lastFullRunId } }"
            >
              （Run を見る）
            </RouterLink>
            · 設定 {{ detail.configVersion ? `v${detail.configVersion}` : '既定値' }}
          </p>

          <!--
            前回比は差分そのもので示し、矢印の色に頼らない。増えて良い指標と
            増えて悪い指標が同じ表に並ぶため、良し悪しは判定列が担う（docs/08 4.2）。
          -->
          <table v-if="metrics.length > 0" class="qg-table qg-table--stack">
            <caption class="qg-visually-hidden">
              指標ごとの現在値と判定
            </caption>
            <thead>
              <tr>
                <th scope="col">指標</th>
                <th scope="col">現在値</th>
                <th scope="col">しきい値</th>
                <th scope="col">前回比</th>
                <th scope="col">判定</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="metric in metrics"
                :key="`${metric.metricId}/${metric.componentName ?? ''}/${metric.variant ?? ''}`"
              >
                <td data-label="指標">{{ metricLabel(metric) }}</td>
                <td data-label="現在値">
                  {{
                    metric.status === 'NOT_APPLICABLE'
                      ? '—'
                      : formatValue(metric.value, metric.unit)
                  }}
                </td>
                <td data-label="しきい値">{{ formatThreshold(metric.threshold, metric.unit) }}</td>
                <td data-label="前回比">{{ formatDelta(metric.delta, metric.unit) }}</td>
                <td data-label="判定"><StatusChip :status="metric.status" /></td>
              </tr>
            </tbody>
          </table>
        </template>
      </section>

      <section class="qg-panel" aria-labelledby="runs-heading">
        <h2 id="runs-heading">直近の Run</h2>
        <p v-if="recentRuns.length === 0" class="qg-empty">Run はまだありません。</p>
        <table v-else class="qg-table qg-table--stack">
          <thead>
            <tr>
              <th scope="col">計測日時</th>
              <th scope="col">ブランチ</th>
              <th scope="col">コミット</th>
              <th scope="col">判定</th>
              <th scope="col">計測</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="run in recentRuns" :key="run.runId">
              <td data-label="計測日時">
                <RouterLink :to="{ name: 'run', params: { runId: run.runId } }">
                  {{ formatDateTime(run.measuredAt) }}
                </RouterLink>
              </td>
              <td data-label="ブランチ">
                {{ run.branch
                }}<span v-if="run.pullRequestNumber"> (PR #{{ run.pullRequestNumber }})</span>
              </td>
              <td data-label="コミット">
                <code>{{ shortSha(run.commitSha) }}</code>
              </td>
              <td data-label="判定">
                <StatusChip
                  v-if="run.status === 'EVALUATED'"
                  :status="verdictToStatus(run.verdict)"
                />
                <span v-else-if="run.status === 'FAILED'">◆ 処理失敗</span>
                <span v-else class="qg-muted">{{ run.status }}</span>
              </td>
              <td data-label="計測">
                {{
                  run.completeness === 'FULL'
                    ? '完全計測'
                    : run.completeness === 'PARTIAL'
                      ? '部分計測'
                      : '—'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <section
        v-if="detail.components.length > 0"
        class="qg-panel"
        aria-labelledby="components-heading"
      >
        <h2 id="components-heading">コンポーネント</h2>
        <ul>
          <li v-for="component in detail.components" :key="component.name">
            {{ component.name }}（{{ component.language }}）:
            <code>{{ component.pathPatterns.join(', ') }}</code>
          </li>
        </ul>
      </section>
    </template>
  </section>
</template>

<style scoped>
.qg-repo-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem 1.5rem;
}
.qg-repo-head__links {
  display: flex;
  gap: 1rem;
}
.qg-latest {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}
h2 {
  font-size: 1.0625rem;
  margin-top: 0;
}
</style>
