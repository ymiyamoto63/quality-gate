<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StatusChip from '@/components/StatusChip.vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'
import { formatDateTime, formatValue, shortSha } from '@/api/format'
import { verdictToStatus, type DisplayStatus } from '@/api/status'

type Report = components['schemas']['ReleaseReportResponse']
type Row = Report['metrics'][number]
type Decision = Report['decision']
type HistoryItem = components['schemas']['ReleaseHistoryItem']

/**
 * リリース判定（アプリの画面はこれ 1 つ）。
 *
 * 読み手に開発に携わっていない方も想定し、結論（リリース可 / 不可）を最上部に大きく出してから、
 * 指標ごとの合否、各指標の説明と基準の根拠、判定の履歴の順に並べる。判定・理由・説明の文言はサーバが持つ。
 * 指定が無ければ最新の計測を見せる。タグ・コミットの指定は URL の ?ref= に置き、同じ判定を URL で共有できる。
 */
const route = useRoute()
const router = useRouter()

const currentRef = computed(() => (typeof route.query.ref === 'string' ? route.query.ref : ''))
const input = ref(currentRef.value)
const report = ref<Report | null>(null)
const history = ref<HistoryItem[]>([])
const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')

const DECISIONS: Record<Decision, { label: string; status: DisplayStatus }> = {
  RELEASABLE: { label: 'リリース可', status: 'PASS' },
  NOT_RELEASABLE: { label: 'リリース不可', status: 'FAIL' },
  NOT_MEASURED: { label: '未計測', status: 'NOT_MEASURED' },
}

const decision = computed(() => (report.value ? DECISIONS[report.value.decision] : null))
const summaries = computed(
  () => new Map((report.value?.guides ?? []).map((g) => [g.metricId, g.summary])),
)

async function load(ref: string): Promise<void> {
  state.value = 'loading'
  const { data, error } = await api.GET('/api/v1/release', {
    params: { query: ref ? { ref } : {} },
  })
  if (error || !data) {
    report.value = null
    state.value = 'error'
    errorMessage.value = messageOf(error, 'リリース判定を取得できませんでした')
    return
  }
  report.value = data
  document.title = `リリース判定${ref ? ` ${ref}` : ''} | ${data.repositoryFullName} | quality-gate`
  state.value = 'ready'
}

async function loadHistory(): Promise<void> {
  const { data } = await api.GET('/api/v1/release/history')
  history.value = data?.items ?? []
}

function submit(): void {
  const ref = input.value.trim()
  if (ref === currentRef.value) {
    void load(ref)
  } else {
    void router.push({ query: ref ? { ref } : {} })
  }
}

watch(
  currentRef,
  (ref) => {
    input.value = ref
    void load(ref)
  },
  { immediate: true },
)
void loadHistory()

function qualifier(row: Row): string {
  const parts = [row.componentName, row.variantLabel].filter(Boolean)
  return parts.length > 0 ? `（${parts.join('・')}）` : ''
}

function rowKey(row: Row): string {
  return `${row.metricId}|${row.componentName ?? ''}|${row.variantLabel ?? ''}`
}

function refLabel(item: HistoryItem): string {
  return item.tags.length > 0 ? item.tags.join(', ') : shortSha(item.commitSha)
}

function print(): void {
  window.print()
}
</script>

<template>
  <section class="qg-release">
    <h1>
      リリース判定
      <span v-if="report" class="qg-release__repository">{{ report.repositoryFullName }}</span>
    </h1>

    <form class="qg-release__form qg-no-print" @submit.prevent="submit">
      <label for="release-ref">タグまたはコミット SHA</label>
      <input
        id="release-ref"
        v-model="input"
        type="text"
        placeholder="空欄なら最新の計測"
        aria-describedby="release-ref-hint"
        autocomplete="off"
      />
      <button type="submit" class="qg-button">判定を見る</button>
      <RouterLink v-if="currentRef" to="/">最新の計測に戻る</RouterLink>
      <p id="release-ref-hint" class="qg-muted">
        指定したコミットで計測した結果だけを使います（近くのコミットの結果では代用しません）。
      </p>
    </form>

    <p v-if="state === 'loading'" class="qg-muted">読み込み中…</p>
    <p v-else-if="state === 'error'" role="alert">{{ errorMessage }}</p>

    <template v-else-if="report && decision">
      <!-- 結論を最初に置く。ここを見れば結論がすぐ分かるようにする -->
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
        <p v-if="report.counts.judged > 0" class="qg-release__score">
          品質基準 {{ report.counts.judged }} 項目のうち
          <strong>{{ report.counts.passed }} 項目が合格</strong>
          <template v-if="report.counts.failed > 0"
            >、{{ report.counts.failed }} 項目が不合格</template
          >
        </p>

        <dl class="qg-release__facts">
          <div v-if="report.run && report.run.tags.length > 0">
            <dt>タグ</dt>
            <dd>{{ report.run.tags.join(', ') }}</dd>
          </div>
          <div v-if="report.commitSha">
            <dt>コミット</dt>
            <dd>
              <a v-if="report.commitUrl" :href="report.commitUrl" rel="noopener" target="_blank">
                <code>{{ shortSha(report.commitSha) }}</code>
              </a>
              <code v-else>{{ shortSha(report.commitSha) }}</code>
            </dd>
          </div>
          <div v-if="report.run">
            <dt>計測日時</dt>
            <dd>
              <a
                v-if="report.run.ciRunUrl"
                :href="report.run.ciRunUrl"
                rel="noopener"
                target="_blank"
              >
                {{ formatDateTime(report.run.measuredAt) }}
              </a>
              <template v-else>{{ formatDateTime(report.run.measuredAt) }}</template>
            </dd>
          </div>
          <div v-if="report.run?.baseCommitSha">
            <dt>比較元</dt>
            <dd>
              <code>{{ shortSha(report.run.baseCommitSha) }}</code>
              （破壊的変更・スキップの増加は、ここからの差で数えています）
            </dd>
          </div>
        </dl>

        <p class="qg-release__actions qg-no-print">
          <button type="button" class="qg-button" @click="print">PDF として保存（印刷）</button>
        </p>
      </section>

      <section v-if="report.metrics.length > 0" aria-labelledby="metrics-heading">
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
            <tr v-for="row in report.metrics" :key="rowKey(row)" :data-status="row.status">
              <td data-label="判定"><StatusChip :status="row.status" /></td>
              <th scope="row" data-label="指標">
                {{ row.name }}<span class="qg-muted">{{ qualifier(row) }}</span>
              </th>
              <td data-label="何を見るか">{{ summaries.get(row.metricId) }}</td>
              <td data-label="合格ライン">{{ row.threshold ?? '—' }}</td>
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

      <section v-if="report.guides.length > 0" aria-labelledby="guides-heading">
        <h2 id="guides-heading">各指標の説明と基準の根拠</h2>
        <p class="qg-muted">
          根拠は既定の合格ラインについての説明です。根拠の種類は「外部基準」（公的・業界の基準がある）、
          「業界の目安」（広く使われる目安）、「チーム判断」（このプロジェクトで決めた値。見直しの対象）の
          3 つです。
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

    <section v-if="history.length > 0" class="qg-no-print" aria-labelledby="history-heading">
      <h2 id="history-heading">判定の履歴</h2>
      <table class="qg-table qg-table--stack">
        <thead>
          <tr>
            <th scope="col">判定</th>
            <th scope="col">タグ・コミット</th>
            <th scope="col">計測日時</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in history" :key="item.commitSha">
            <td data-label="判定"><StatusChip :status="verdictToStatus(item.verdict)" /></td>
            <th scope="row" data-label="タグ・コミット">
              <RouterLink :to="{ query: { ref: item.ref } }">{{ refLabel(item) }}</RouterLink>
            </th>
            <td data-label="計測日時">{{ formatDateTime(item.measuredAt) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>

<style scoped>
h1 {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.25rem 0.75rem;
}
.qg-release__repository {
  font-size: 1rem;
  font-weight: normal;
  color: var(--text-secondary);
}
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
  border-left-width: 8px;
  border-radius: var(--radius);
  background: var(--surface-1);
  padding: 1.25rem 1.5rem;
  margin-bottom: 1.5rem;
}
/* 色だけに頼らず、見出しの文言とチップの記号でも結論が分かるようにしている */
.qg-release__decision[data-decision='RELEASABLE'] {
  border-left-color: var(--status-pass);
}
.qg-release__decision[data-decision='NOT_RELEASABLE'] {
  border-left-color: var(--status-fail);
}
.qg-release__decision[data-decision='NOT_MEASURED'] {
  border-left-color: var(--status-neutral);
}
.qg-release__verdict {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 2rem;
  margin: 0;
}
.qg-release__mark :deep(.qg-status__label) {
  display: none;
}
.qg-release__mark :deep(.pi),
.qg-release__mark :deep(.qg-status__mark) {
  font-size: 1.5rem;
}
.qg-release__reason {
  font-size: 1.0625rem;
  margin: 0.5rem 0;
}
.qg-release__score {
  font-size: 1.0625rem;
  margin: 0 0 1rem;
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
  margin-top: 2rem;
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
  .qg-release__verdict {
    font-size: 1.5rem;
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
