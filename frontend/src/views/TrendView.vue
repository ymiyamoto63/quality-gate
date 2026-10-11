<script setup lang="ts">
import { ref } from 'vue'
import TrendChart from '@/components/TrendChart.vue'
import { api, messageOf } from '@/api/client'
import type { components } from '@/api/schema'

type Trends = components['schemas']['MetricTrendResponse']

/**
 * 指標の推移（docs/features/trends/requirements.md）。
 *
 * リリース判定の補助。結論はリリース判定の画面が示し、ここでは合格ライン内でも値が悪くなっていないかを
 * 計測をまたいで見せる。並びはリリース判定と同じ指標 ID の順、計測は判定の履歴と同じ数え方。
 */
const trends = ref<Trends | null>(null)
const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')

async function load(): Promise<void> {
  state.value = 'loading'
  const { data, error } = await api.GET('/api/v1/release/trends')
  if (error || !data) {
    state.value = 'error'
    errorMessage.value = messageOf(error, '指標の推移を取得できませんでした')
    return
  }
  trends.value = data
  state.value = 'ready'
}

void load()

function print(): void {
  window.print()
}
</script>

<template>
  <section class="qg-trends">
    <h1>指標の推移</h1>
    <p class="qg-muted qg-trends__lead">
      判定まで終わった計測（同じコミットは最後の判定だけ）を、新しいものから最大 50
      件、古い順に並べています。
    </p>
    <p class="qg-muted qg-trends__lead">
      点線は、それぞれの計測の判定に使った合格ラインです。点を選ぶと、その計測のリリース判定を開きます。
    </p>
    <p class="qg-trends__actions qg-no-print">
      <RouterLink :to="{ name: 'release' }">リリース判定に戻る</RouterLink>
      <button
        v-if="state === 'ready' && trends && trends.runs.length > 0"
        type="button"
        class="qg-button"
        @click="print"
      >
        PDF として保存（印刷）
      </button>
    </p>

    <p v-if="state === 'loading'" class="qg-muted">読み込み中…</p>
    <p v-else-if="state === 'error'" role="alert">{{ errorMessage }}</p>
    <p v-else-if="trends && trends.runs.length === 0" class="qg-empty">
      判定まで終わった計測がありません。収集ランナーで計測してから、もう一度確認してください。
    </p>
    <template v-else-if="trends">
      <ul class="qg-trends__key" aria-label="点の記号">
        <li><span aria-hidden="true" data-status="PASS">●</span> 合格</li>
        <li><span aria-hidden="true" data-status="FAIL">■</span> 不合格</li>
        <li>
          <span aria-hidden="true" data-status="ERROR">◆</span>
          計測エラー（値が無いため横軸の上に置き、線を途切れさせます）
        </li>
        <li><span aria-hidden="true">—</span> 対象外</li>
      </ul>
      <TrendChart
        v-for="metric in trends.metrics"
        :key="metric.metricId"
        :metric="metric"
        :runs="trends.runs"
      />
    </template>
  </section>
</template>

<style scoped>
h1 {
  margin-bottom: 0.5rem;
}
.qg-trends__lead {
  margin: 0.25rem 0;
}
.qg-trends__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem 1.5rem;
  margin: 0.75rem 0 1.25rem;
}
.qg-trends__key {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1.25rem;
  list-style: none;
  padding: 0;
  margin: 0 0 1rem;
  font-size: 0.875rem;
}
.qg-trends__key span {
  color: var(--status-neutral);
}
.qg-trends__key [data-status='PASS'] {
  color: var(--status-pass);
}
.qg-trends__key [data-status='FAIL'] {
  color: var(--status-fail);
}
.qg-trends__key [data-status='ERROR'] {
  color: var(--status-error);
}
@media print {
  .qg-no-print {
    display: none;
  }
}
</style>
