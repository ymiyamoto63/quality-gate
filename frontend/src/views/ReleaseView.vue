<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ReleaseDecision from '@/components/release/ReleaseDecision.vue'
import ReleaseGuides from '@/components/release/ReleaseGuides.vue'
import ReleaseHistory from '@/components/release/ReleaseHistory.vue'
import ReleaseMetricsTable from '@/components/release/ReleaseMetricsTable.vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'

type Report = components['schemas']['ReleaseReportResponse']
type HistoryItem = components['schemas']['ReleaseHistoryItem']

/**
 * リリース判定（アプリの主画面。指標の推移はその補助）。
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

    <template v-else-if="report">
      <!-- 結論を最初に置く。ここを見れば結論がすぐ分かるようにする -->
      <ReleaseDecision :report="report" />
      <ReleaseMetricsTable :metrics="report.metrics" :guides="report.guides" />
      <ReleaseGuides :guides="report.guides" />
    </template>

    <ReleaseHistory :items="history" />
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
@media (max-width: 767px) {
  .qg-release__form input {
    min-width: 0;
    flex: 1;
  }
}
@media print {
  .qg-no-print {
    display: none;
  }
}
</style>
