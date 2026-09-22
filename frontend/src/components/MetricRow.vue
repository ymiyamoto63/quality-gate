<script setup lang="ts">
import { computed } from 'vue'
import StatusChip from './StatusChip.vue'
import { formatValue, formatDelta, formatThreshold } from '@/api/format'
import type { RunMetric } from '@/stores/run'

/**
 * Run 詳細の指標 1 行。
 *
 * 判定理由（reason）はサーバが返した文をそのまま出す。ここで文言を組み立てると、
 * 「しきい値をどう解釈したか」の表現がサーバと画面の 2 箇所に分かれる。
 */
const props = defineProps<{ metric: RunMetric; runId: string }>()

// 計測条件（M-02 の実行範囲など）は名前に添える。変更範囲だけの値を
// 全量の値と読み違えると、スコアの高低を取り違える
const label = computed(() => {
  const qualifiers = [props.metric.componentName, props.metric.variantLabel].filter(Boolean)
  return qualifiers.length > 0
    ? `${props.metric.name}（${qualifiers.join('・')}）`
    : props.metric.name
})
// 対象外は「未計測」と出さない。測りようがないものを測り忘れに見せないため
const value = computed(() =>
  props.metric.status === 'NOT_APPLICABLE'
    ? '—'
    : formatValue(props.metric.value, props.metric.unit),
)
/**
 * 指標そのものの限界。判定結果によらず常に添える（docs/02-metrics-spec.md M-10）。
 * 「重大 0 件」を適合の証明と受け取られると、手動での確認が省かれる。
 */
const NOTES: Record<string, string> = {
  'M-10':
    '自動検査で検出できる WCAG 違反は一部です。重大 0 件は適合の必要条件であって十分条件ではありません。キーボード操作とスクリーンリーダーでの確認は別に必要です。',
}
const note = computed(() => NOTES[props.metric.metricId] ?? null)
const threshold = computed(() => formatThreshold(props.metric.threshold, props.metric.unit))
const delta = computed(() => formatDelta(props.metric.delta, props.metric.unit))
</script>

<template>
  <div class="qg-metric">
    <div class="qg-metric__line">
      <span class="qg-metric__name">{{ label }}</span>
      <span class="qg-metric__value">{{ value }}</span>
      <span class="qg-metric__threshold">{{ threshold ?? '' }}</span>
      <!--
        差分の良し悪しは指標ごとに向きが逆（カバレッジは増えると良い、
        脆弱性件数は減ると良い）。判断はサーバの deltaImproved に従う。

        色は記号にだけ付け、数字はインク色のままにする（StatusChip と同じ方針）。
        ステータス色を文字色に使うと、ライト面の緑もダーク面の赤も
        4.5:1 を下回る。記号と読み上げ語で意味を担保する。
      -->
      <span v-if="delta" class="qg-metric__delta">
        <span
          v-if="metric.deltaImproved !== null"
          class="qg-metric__arrow"
          :data-improved="String(metric.deltaImproved)"
          aria-hidden="true"
          >{{ metric.deltaImproved ? '▲' : '▼' }}</span
        >
        {{ delta }}
        <span class="qg-visually-hidden">
          {{ metric.deltaImproved === null ? '前回比' : metric.deltaImproved ? '改善' : '悪化' }}
        </span>
      </span>
      <StatusChip :status="metric.status" />
    </div>

    <p v-if="metric.reason" class="qg-metric__reason">{{ metric.reason }}</p>
    <p v-if="note" class="qg-metric__note">
      <i class="pi pi-info-circle" aria-hidden="true" />
      {{ note }}
    </p>

    <RouterLink
      v-if="metric.findingCount > 0"
      class="qg-metric__link"
      :to="{ name: 'findings', params: { runId }, query: { metricId: metric.metricId } }"
    >
      違反 {{ metric.findingCount }} 件を見る →
    </RouterLink>
  </div>
</template>

<style scoped>
.qg-metric {
  padding: 0.6rem 0;
  border-top: 1px solid var(--border);
}

.qg-metric__line {
  display: grid;
  grid-template-columns: minmax(12rem, 2fr) 6rem 5rem 5rem auto;
  align-items: center;
  gap: 0.5rem;
}

.qg-metric__value {
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.qg-metric__threshold,
.qg-metric__delta {
  font-variant-numeric: tabular-nums;
  text-align: right;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

/* 色が付くのは記号だけ。数字はインク色のまま読めることを優先する */
.qg-metric__arrow[data-improved='true'] {
  color: var(--status-pass);
}
.qg-metric__arrow[data-improved='false'] {
  color: var(--status-fail);
}

.qg-metric__reason {
  margin: 0.25rem 0 0;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.qg-metric__note {
  margin: 0.25rem 0 0;
  font-size: 0.8125rem;
  color: var(--text-secondary);
}

.qg-metric__link {
  display: inline-block;
  margin-top: 0.35rem;
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

@media (max-width: 767px) {
  .qg-metric__line {
    grid-template-columns: 1fr auto;
  }
  .qg-metric__threshold,
  .qg-metric__delta {
    text-align: left;
  }
}
</style>
