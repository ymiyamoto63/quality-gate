<script setup lang="ts">
import { computed, ref } from 'vue'
import { plot, type RawSeries, type PlottedPoint, type PlottedSeries } from './trendGeometry'
import { formatValue, formatDateTime } from '@/api/format'

/**
 * トレンドグラフ。
 *
 * canvas ではなくインライン SVG で描く（docs/spec/04-tech-stack.md 3.1）。
 * 読み上げのための代替表現が別途必要にならず、配色トークンがそのまま効き、
 * ダークモードに CSS だけで追従する。
 *
 * グラフを描くのはこのコンポーネントだけに閉じてあるため、
 * 描画方法を差し替える場合もここだけで済む。
 */
const props = defineProps<{
  series: RawSeries[]
  unit: string | null
  thresholdValue: number | null
  thresholdLabel: string | null
  /** グラフが何を示しているかの説明。図の代替テキストになる */
  caption: string
}>()

const hovered = ref<{ series: PlottedSeries; point: PlottedPoint } | null>(null)

const chart = computed(() =>
  plot(props.series, {
    thresholdValue: props.thresholdValue,
    // 件数は 0 に意味がある（違反なし）。割合は 0 起点にすると変化が潰れる
    includeZero: props.unit === 'count',
  }),
)

const hasValues = computed(() => props.series.some((s) => s.points.some((p) => p.value !== null)))

function colorOf(series: PlottedSeries): string {
  return `var(--series-${series.colorIndex + 1})`
}

function show(series: PlottedSeries, point: PlottedPoint): void {
  if (point.y !== null) hovered.value = { series, point }
}

/**
 * 直接ラベル。右余白に収まらない長さは切り詰める。
 *
 * はみ出したラベルを切り抜く（overflow: hidden）と、末尾の文字が欠けたまま
 * 読めない表示になる。凡例と表には完全な名前があるため、ここは省略でよい。
 */
function labelOf(series: PlottedSeries): string {
  const limit = 10
  return series.label.length > limit ? `${series.label.slice(0, limit)}…` : series.label
}
</script>

<template>
  <figure class="qg-chart">
    <!--
      図そのものに説明を持たせる。SVG は DOM なので、canvas と違って
      中の要素にラベルを付けられる。
    -->
    <svg
      :viewBox="`0 0 ${chart.width} ${chart.height}`"
      role="img"
      :aria-label="caption"
      class="qg-chart__svg"
      @mouseleave="hovered = null"
    >
      <!-- 目盛り線。データより後ろに置き、ヘアラインで recessive に保つ -->
      <g class="qg-chart__grid">
        <line
          v-for="tick in chart.yTicks"
          :key="`grid-${tick.value}`"
          :x1="chart.padding.left"
          :x2="chart.width - chart.padding.right"
          :y1="tick.y"
          :y2="tick.y"
        />
      </g>

      <g class="qg-chart__axis-text">
        <text
          v-for="tick in chart.yTicks"
          :key="`y-${tick.value}`"
          :x="chart.padding.left - 8"
          :y="tick.y"
          text-anchor="end"
          dominant-baseline="middle"
        >
          {{ tick.label }}
        </text>
        <text
          v-for="tick in chart.xTicks"
          :key="`x-${tick.x}`"
          :x="tick.x"
          :y="chart.height - chart.padding.bottom + 18"
          text-anchor="middle"
        >
          {{ tick.label }}
        </text>
      </g>

      <!--
        しきい値は破線のヘアライン。status 色を使わないのは、点の判定色と
        競合するため。基準を赤で描くと、その近くの合格の点まで危険に見える。
      -->
      <g v-if="chart.thresholdY !== null" class="qg-chart__threshold">
        <line
          :x1="chart.padding.left"
          :x2="chart.width - chart.padding.right"
          :y1="chart.thresholdY"
          :y2="chart.thresholdY"
        />
        <!--
          ラベルは線の真上・作図領域の内側に置く。右の余白に出すと、
          系列の直接ラベルと場所を取り合い、長い単位で切れる。
        -->
        <text :x="chart.width - chart.padding.right" :y="chart.thresholdY - 6" text-anchor="end">
          {{ thresholdLabel }}
        </text>
      </g>

      <g v-for="s in chart.series" :key="s.seriesId">
        <path
          v-for="(d, i) in s.segments"
          :key="`${s.seriesId}-${i}`"
          :d="d"
          fill="none"
          :stroke="colorOf(s)"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        />

        <g v-for="point in s.points" :key="point.runId">
          <circle
            v-if="point.y !== null"
            :cx="point.x"
            :cy="point.y"
            r="4"
            :fill="colorOf(s)"
            class="qg-chart__marker"
            :data-failed="point.status === 'FAIL' || point.status === 'ERROR'"
          />
          <!--
            当たり判定はマーカーより大きく取る。半径 4px の点は狙いにくい。
          -->
          <circle
            :cx="point.x"
            :cy="point.y ?? chart.height - chart.padding.bottom"
            r="12"
            fill="transparent"
            @mouseenter="show(s, point)"
          />
        </g>

        <!--
          直接ラベルは最後の点にだけ置く。すべての点に数字を出すと読まれなくなる。
          文字はインク色のまま。系列色を文字に使うと、明るい色が読めない。
        -->
        <text
          v-if="s.lastValued"
          :x="s.lastValued.x + 10"
          :y="s.lastValued.y!"
          dominant-baseline="middle"
          class="qg-chart__direct-label"
        >
          {{ labelOf(s) }}
        </text>
      </g>
    </svg>

    <!-- 系列が 2 つ以上あれば凡例を必ず出す。色の対応づけだけに頼らせない -->
    <ul v-if="series.length > 1" class="qg-chart__legend">
      <li v-for="s in chart.series" :key="s.seriesId">
        <svg width="24" height="8" aria-hidden="true">
          <line x1="0" y1="4" x2="24" y2="4" :stroke="colorOf(s)" stroke-width="2" />
        </svg>
        {{ s.label }}
      </li>
    </ul>

    <p v-if="hovered" class="qg-chart__tooltip" role="status">
      {{ hovered.series.label }} · {{ formatDateTime(hovered.point.measuredAt) }} ·
      {{ formatValue(hovered.point.value, unit) }}
      <RouterLink :to="{ name: 'run', params: { runId: hovered.point.runId } }">
        Run を見る →
      </RouterLink>
    </p>

    <figcaption class="qg-chart__caption">
      {{ caption }}
      <span v-if="!hasValues">（この期間に計測値がありません）</span>
    </figcaption>
  </figure>
</template>

<style scoped>
.qg-chart {
  margin: 0;
}

.qg-chart__svg {
  width: 100%;
  height: auto;
}

/* 目盛りは実線のヘアライン。破線はしきい値のために取ってある */
.qg-chart__grid line {
  stroke: var(--gridline);
  stroke-width: 1;
}

.qg-chart__axis-text text,
.qg-chart__direct-label {
  font-size: 11px;
  fill: var(--text-secondary);
}

.qg-chart__direct-label {
  font-size: 12px;
}

.qg-chart__threshold line {
  stroke: var(--text-muted);
  stroke-width: 1;
  stroke-dasharray: 4 4;
}

/*
 * 文字の周りを面の色で縁取る。ラベルがデータの線と重なっても読める。
 * 位置で衝突を避けようとしても、データはどこにでも来るため避けきれない。
 */
.qg-chart__threshold text,
.qg-chart__direct-label {
  paint-order: stroke;
  stroke: var(--surface-1);
  stroke-width: 3px;
  stroke-linejoin: round;
}

.qg-chart__threshold text {
  font-size: 11px;
  fill: var(--text-secondary);
}

/* 面の色でリングを入れ、線や他の点と重なっても輪郭が残る */
.qg-chart__marker {
  stroke: var(--surface-1);
  stroke-width: 2;
}

/* 不合格の点は大きくし、status 色のリングを付ける */
.qg-chart__marker[data-failed='true'] {
  r: 6;
  stroke: var(--status-fail);
}

.qg-chart__legend {
  display: flex;
  gap: 1.25rem;
  flex-wrap: wrap;
  list-style: none;
  padding: 0;
  margin: 0.5rem 0 0;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.qg-chart__legend li {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.qg-chart__tooltip {
  margin: 0.5rem 0 0;
  font-size: 0.875rem;
}

.qg-chart__caption {
  margin-top: 0.5rem;
  font-size: 0.875rem;
  color: var(--text-secondary);
}
</style>
