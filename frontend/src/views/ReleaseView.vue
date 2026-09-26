<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StatusChip from '@/components/StatusChip.vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'
import { formatDateTime, formatValue, shortSha } from '@/api/format'
import type { MeasurementStatus } from '@/api/status'

type Report = components['schemas']['ReleaseReportResponse']
type Row = Report['metrics'][number]
type Decision = Report['decision']

/**
 * リリース判定（S-11。UC-10）。
 *
 * 読み手に経営陣や開発に詳しくない人を想定し、結論を最上部に 1 行で出してから、
 * 指標ごとの合否、各指標の説明と基準の根拠の順に並べる。判定・理由・説明の文言はサーバが持つ
 * （CSV の証跡と同じ文言にするため）。指定は URL の ?ref= に置き、同じ判定を URL で共有できるようにする。
 */
const route = useRoute()
const router = useRouter()
const repositoryId = computed(() => String(route.params.repositoryId))

const input = ref(typeof route.query.ref === 'string' ? route.query.ref : '')
const report = ref<Report | null>(null)
const state = ref<'idle' | 'loading' | 'error'>('idle')
const errorMessage = ref('')

const DECISIONS: Record<Decision, { label: string; status: MeasurementStatus }> = {
  RELEASABLE: { label: 'リリース可', status: 'PASS' },
  RELEASABLE_WITH_WARNINGS: { label: 'リリース可（注意あり）', status: 'WARN' },
  NOT_RELEASABLE: { label: 'リリース不可', status: 'FAIL' },
  UNDETERMINED: { label: '判定できない', status: 'SKIP' },
}

const decision = computed(() => (report.value ? DECISIONS[report.value.decision] : null))
const judgedRows = computed(() => report.value?.metrics.filter((m) => !m.referenceOnly) ?? [])
const referenceRows = computed(() => report.value?.metrics.filter((m) => m.referenceOnly) ?? [])
const summaries = computed(
  () => new Map((report.value?.guides ?? []).map((g) => [g.metricId, g.summary])),
)
const csvUrl = computed(() =>
  report.value
    ? `/api/v1/repositories/${repositoryId.value}/release-report.csv?${new URLSearchParams({ ref: report.value.ref })}`
    : '',
)

async function load(ref: string): Promise<void> {
  if (!ref.trim()) {
    report.value = null
    state.value = 'idle'
    return
  }
  state.value = 'loading'
  const { data, error } = await api.GET('/api/v1/repositories/{repositoryId}/release-report', {
    params: { path: { repositoryId: repositoryId.value }, query: { ref } },
  })
  if (error || !data) {
    report.value = null
    state.value = 'error'
    errorMessage.value = messageOf(error, 'リリース判定を取得できませんでした')
    return
  }
  report.value = data
  document.title = `リリース判定 ${data.ref} | ${data.repositoryFullName} | quality-gate`
  state.value = 'idle'
}

function submit(): void {
  const ref = input.value.trim()
  if (ref === route.query.ref) {
    void load(ref)
  } else {
    void router.push({ query: { ref } })
  }
}

watch(
  () => [repositoryId.value, route.query.ref] as const,
  ([, ref]) => {
    const value = typeof ref === 'string' ? ref : ''
    input.value = value
    void load(value)
  },
  { immediate: true },
)

function qualifier(row: Row): string {
  const parts = [row.componentName, row.variantLabel, row.scenario].filter(Boolean)
  return parts.length > 0 ? `（${parts.join('・')}）` : ''
}

function print(): void {
  window.print()
}
</script>

<template>
  <section class="qg-release">
    <h1>リリース判定</h1>

    <form class="qg-release__form qg-no-print" @submit.prevent="submit">
      <label for="release-ref">タグまたはコミット SHA</label>
      <input
        id="release-ref"
        v-model="input"
        type="text"
        required
        placeholder="例: v1.2.0 / a1b2c3d"
        aria-describedby="release-ref-hint"
        autocomplete="off"
      />
      <button type="submit" class="qg-button">判定する</button>
      <p id="release-ref-hint" class="qg-muted">
        指定したコミットで計測した結果だけを使います（近くのコミットの結果では代用しません）。
      </p>
    </form>

    <p v-if="state === 'loading'" class="qg-muted">読み込み中…</p>
    <p v-else-if="state === 'error'" role="alert">{{ errorMessage }}</p>

    <template v-else-if="report && decision">
      <!-- 結論を最初に置く。経営陣はここと下の表の 3 列だけ読めば足りるようにする -->
      <section
        class="qg-release__decision"
        :data-decision="report.decision"
        aria-labelledby="decision-heading"
      >
        <h2 id="decision-heading" class="qg-release__verdict">
          <!-- 記号とアイコンだけを借り、文言は見出しの本文で読ませる -->
          <span aria-hidden="true" class="qg-release__mark">
            <StatusChip :status="decision.status" />
          </span>
          {{ decision.label }}
        </h2>
        <p class="qg-release__reason">{{ report.decisionReason }}</p>

        <dl class="qg-release__facts">
          <div>
            <dt>リポジトリ</dt>
            <dd>{{ report.repositoryFullName }}</dd>
          </div>
          <div v-if="report.refType === 'TAG'">
            <dt>タグ</dt>
            <dd>{{ report.ref }}</dd>
          </div>
          <div>
            <dt>コミット</dt>
            <dd>
              <a :href="report.commitUrl" rel="noopener" target="_blank">
                <code>{{ shortSha(report.commitSha) }}</code>
              </a>
            </dd>
          </div>
          <div v-if="report.run">
            <dt>計測日時</dt>
            <dd>
              <RouterLink :to="{ name: 'run', params: { runId: report.run.runId } }">
                {{ formatDateTime(report.run.measuredAt) }}
              </RouterLink>
              （{{ report.run.completeness === 'FULL' ? '完全計測' : '部分計測' }}）
            </dd>
          </div>
          <div v-if="report.run">
            <dt>合格ライン</dt>
            <dd>{{ report.gateConfig ? `v${report.gateConfig.version}` : '既定値' }}</dd>
          </div>
          <div v-if="report.gateConfig && report.gateConfig.exclusions.length > 0">
            <dt>計測から除外</dt>
            <dd>
              <code>{{ report.gateConfig.exclusions.join(', ') }}</code>
            </dd>
          </div>
          <div v-if="report.otherRunCount > 0">
            <dt>同じコミットのほかの計測</dt>
            <dd>{{ report.otherRunCount }} 件（判定には使っていません）</dd>
          </div>
        </dl>

        <p class="qg-release__actions qg-no-print">
          <a class="qg-button" :href="csvUrl" download>CSV をダウンロード（証跡）</a>
          <button type="button" class="qg-button" @click="print">PDF として保存（印刷）</button>
        </p>
      </section>

      <section v-if="judgedRows.length > 0" aria-labelledby="metrics-heading">
        <h2 id="metrics-heading">指標ごとの合否</h2>
        <p class="qg-muted">
          合否に使う {{ report.counts.judged }} 件: 合格 {{ report.counts.passed }} ／ 注意
          {{ report.counts.warned }} ／ 不合格 {{ report.counts.failed }} ／ 計測エラー
          {{ report.counts.errored }}
          <template v-if="report.counts.skipped > 0">
            ／ 未計測 {{ report.counts.skipped }}</template
          >。問題のある指標を先に並べています。
        </p>
        <table class="qg-table qg-table--stack">
          <thead>
            <tr>
              <th scope="col">指標</th>
              <th scope="col">何を見るか</th>
              <th scope="col">基準</th>
              <th scope="col">結果</th>
              <th scope="col">判定</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in judgedRows"
              :key="`${row.metricId}|${row.componentName ?? ''}|${row.variantLabel ?? ''}|${row.scenario ?? ''}`"
            >
              <th scope="row" data-label="指標">
                {{ row.name }}<span class="qg-muted">{{ qualifier(row) }}</span>
              </th>
              <td data-label="何を見るか">{{ summaries.get(row.metricId) }}</td>
              <td data-label="基準">{{ row.threshold ?? '—' }}</td>
              <td data-label="結果">
                {{ row.status === 'NOT_APPLICABLE' ? '—' : formatValue(row.value, row.unit) }}
              </td>
              <td data-label="判定"><StatusChip :status="row.status" /></td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-if="referenceRows.length > 0" aria-labelledby="reference-heading">
        <h2 id="reference-heading">参考値（合否には使いません）</h2>
        <table class="qg-table qg-table--stack">
          <thead>
            <tr>
              <th scope="col">指標</th>
              <th scope="col">何を見るか</th>
              <th scope="col">結果</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in referenceRows"
              :key="`${row.metricId}|${row.componentName ?? ''}|${row.scenario ?? ''}`"
            >
              <th scope="row" data-label="指標">
                {{ row.name }}<span class="qg-muted">{{ qualifier(row) }}</span>
              </th>
              <td data-label="何を見るか">{{ summaries.get(row.metricId) }}</td>
              <td data-label="結果">{{ formatValue(row.value, row.unit) }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-if="report.guides.length > 0" aria-labelledby="guides-heading">
        <h2 id="guides-heading">各指標の説明と基準の根拠</h2>
        <p class="qg-muted">
          根拠は既定の基準についての説明です。基準を変えた場合、その理由は合格ラインの変更（コミット）に残っています。
          根拠の種類は「外部基準」（公的・業界の基準がある）、「業界の目安」（広く使われる目安）、
          「チーム判断」（このプロジェクトで決めた値。見直しの対象）の 3 つです。
        </p>
        <article v-for="guide in report.guides" :key="guide.metricId" class="qg-release__guide">
          <h3>
            {{ guide.name }}
            <span class="qg-release__basis" :data-basis="guide.basis">{{ guide.basisLabel }}</span>
          </h3>
          <dl>
            <dt>何を見るか</dt>
            <dd>{{ guide.summary }}</dd>
            <dt>基準の根拠</dt>
            <dd>{{ guide.rationale }}</dd>
            <dt>基準を満たさないと</dt>
            <dd>{{ guide.risk }}</dd>
            <dt>計測ツール</dt>
            <dd>{{ guide.tools }}</dd>
          </dl>
          <details>
            <summary>技術的な定義</summary>
            <p>{{ guide.definition }}</p>
          </details>
        </article>
      </section>
    </template>
  </section>
</template>

<style scoped>
.qg-release__form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
  margin-bottom: 1.5rem;
}
.qg-release__form label {
  font-size: 0.875rem;
}
.qg-release__form input {
  font: inherit;
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-1);
  color: var(--text-primary);
  min-width: 16rem;
}
.qg-release__form p {
  flex-basis: 100%;
  margin: 0;
}
.qg-release__decision {
  border: 1px solid var(--border);
  border-left-width: 6px;
  border-radius: var(--radius);
  background: var(--surface-1);
  padding: 1rem 1.25rem;
  margin-bottom: 1.5rem;
}
/* 色だけに頼らず、見出しの文言とチップの記号でも結論が分かるようにしている */
.qg-release__decision[data-decision='RELEASABLE'] {
  border-left-color: var(--status-pass);
}
.qg-release__decision[data-decision='RELEASABLE_WITH_WARNINGS'] {
  border-left-color: var(--status-warn);
}
.qg-release__decision[data-decision='NOT_RELEASABLE'] {
  border-left-color: var(--status-fail);
}
.qg-release__decision[data-decision='UNDETERMINED'] {
  border-left-color: var(--status-neutral);
}
.qg-release__verdict {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 1.5rem;
  margin: 0;
}
.qg-release__mark :deep(.qg-status__label) {
  display: none;
}
.qg-release__mark :deep(.pi),
.qg-release__mark :deep(.qg-status__mark) {
  font-size: 1.25rem;
}
.qg-release__reason {
  font-size: 1.0625rem;
  margin: 0.5rem 0 1rem;
}
.qg-release__facts {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1.5rem;
  margin: 0;
}
.qg-release__facts dt {
  font-size: 0.8rem;
  color: var(--text-secondary);
}
.qg-release__facts dd {
  margin: 0;
}
.qg-release__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin: 1rem 0 0;
}
h2 {
  font-size: 1.0625rem;
}
.qg-release__guide {
  border-top: 1px solid var(--border);
  padding: 0.75rem 0;
  break-inside: avoid;
}
.qg-release__guide h3 {
  font-size: 1rem;
  margin: 0 0 0.5rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.qg-release__basis {
  font-size: 0.75rem;
  font-weight: normal;
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 0.05rem 0.5rem;
  color: var(--text-primary);
}
.qg-release__guide dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.25rem 1rem;
  margin: 0 0 0.5rem;
}
.qg-release__guide dt {
  color: var(--text-secondary);
  font-size: 0.875rem;
}
.qg-release__guide dd {
  margin: 0;
}
.qg-release__guide summary {
  font-size: 0.875rem;
  cursor: pointer;
}
@media (max-width: 767px) {
  .qg-release__guide dl {
    grid-template-columns: 1fr;
  }
  .qg-release__form input {
    min-width: 0;
    flex: 1;
  }
}
@media print {
  .qg-no-print {
    display: none;
  }
  .qg-release__decision {
    break-inside: avoid;
  }
}
</style>
