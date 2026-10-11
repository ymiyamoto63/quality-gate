<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import StatusChip from '@/components/StatusChip.vue'
import type { components } from '@/api/schema'
import { formatDateTime, formatValue, shortSha } from '@/api/format'
import { presentationOf } from '@/api/status'
import { axisOf, segmentsOf, stepPathOf, xOf } from '@/api/trend'

type Metric = components['schemas']['TrendMetric']
type Series = Metric['series'][number]
type Point = Series['points'][number]
type Run = components['schemas']['TrendRun']

/**
 * 1 指標の推移（折れ線）。
 *
 * 線はコンポーネント・計測条件ごとに分け、色・線の種類・凡例の 3 つで見分けられるようにする（色だけに頼らない）。
 * 点はステータスの記号（● 合格 / ■ 不合格 / ◆ 計測エラー / — 対象外）とステータス色で描き、
 * 選ぶとその計測のリリース判定を開く。値の無い点（計測エラー・対象外）は横軸の上に置き、線を途切れさせる。
 */
const props = defineProps<{ metric: Metric; runs: Run[] }>()
const router = useRouter()

const HEIGHT = 220
const LEFT = 56
const RIGHT = 16
const TOP = 12
const BOTTOM = 32
const PLOT_HEIGHT = HEIGHT - TOP - BOTTOM
const BASELINE = TOP + PLOT_HEIGHT

/**
 * 描く幅は表示する幅に合わせる。viewBox を固定して拡大縮小すると、
 * 広い画面では文字が大きく、狭い画面では読めないほど小さくなる。
 */
const plot = ref<HTMLElement | null>(null)
const width = ref(720)
const plotWidth = computed(() => Math.max(width.value - LEFT - RIGHT, 1))
let observer: ResizeObserver | null = null
onMounted(() => {
  if (!plot.value) return
  width.value = plot.value.clientWidth || width.value
  if (typeof ResizeObserver === 'undefined') return
  observer = new ResizeObserver(([entry]) => {
    if (entry && entry.contentRect.width > 0) width.value = entry.contentRect.width
  })
  observer.observe(plot.value)
})
onBeforeUnmount(() => observer?.disconnect())

/** 検証済みの系列色は 3 色だけ（tokens.css）。4 本目からは色を作らず、控えめな色と線の種類で分ける。 */
const COLORS = ['var(--series-1)', 'var(--series-2)', 'var(--series-3)']
const DASHES = ['', '8 4', '2 4', '10 3 2 3']

const axis = computed(() =>
  axisOf(
    props.metric.axisMax ?? null,
    props.metric.series.flatMap((s) => s.points.flatMap((p) => [p.value, p.thresholdValue])),
    props.metric.unit === 'count',
  ),
)

function x(runIndex: number): number {
  return xOf(runIndex, props.runs.length, LEFT, plotWidth.value)
}

function y(value: number): number {
  return BASELINE - (Math.min(value, axis.value.max) / axis.value.max) * PLOT_HEIGHT
}

function colorOf(index: number): string {
  return COLORS[index] ?? 'var(--text-muted)'
}

function dashOf(index: number): string {
  return DASHES[index % DASHES.length] ?? ''
}

function seriesLabel(series: Series): string {
  const parts = [series.componentName, series.variantLabel].filter(Boolean)
  return parts.length > 0 ? parts.join('・') : '全体'
}

const lines = computed(() =>
  props.metric.series.map((series, index) => ({
    key: seriesLabel(series),
    label: seriesLabel(series),
    color: colorOf(index),
    dash: dashOf(index),
    paths: segmentsOf(
      series.points.map((p) => ({
        runIndex: p.runIndex,
        y: p.value === null ? null : y(p.value),
      })),
    ).map((segment) =>
      segment.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.runIndex)} ${p.y}`).join(' '),
    ),
    points: series.points,
  })),
)

/** 合格ラインは指標ごとに 1 本。コンポーネントごとに値が違っても、合格ラインは同じ環境変数から決まる。 */
const thresholdPath = computed(() => {
  const byRun = new Map<number, number>()
  for (const series of props.metric.series) {
    for (const point of series.points) {
      if (point.thresholdValue !== null && !byRun.has(point.runIndex)) {
        byRun.set(point.runIndex, point.thresholdValue)
      }
    }
  }
  if (byRun.size === 0) return ''
  return stepPathOf(
    props.runs.map((_, i) => {
      const value = byRun.get(i)
      return { x: x(i), y: value === undefined ? null : y(value) }
    }),
    LEFT,
    LEFT + plotWidth.value,
  )
})

/** 横軸のラベルは重ならないよう、幅に応じて間引く（1 つに 96px）。最初と最後の計測は必ず出す。 */
const xLabels = computed(() => {
  const count = props.runs.length
  const fits = Math.max(2, Math.floor(plotWidth.value / 96))
  const every = Math.max(1, Math.ceil(count / fits))
  const shown = new Set<number>()
  for (let index = 0; index < count; index += every) shown.add(index)
  // 末尾に近すぎるラベルは、末尾と重なるため外す
  const lastShown = Math.max(...shown)
  if (lastShown > 0 && count - 1 - lastShown < every / 2) shown.delete(lastShown)
  if (count > 0) shown.add(count - 1)
  return props.runs
    .map((run, index) => ({ run, index }))
    .filter(({ index }) => shown.has(index))
    .map(({ run, index }) => ({
      key: run.commitSha,
      x: x(index),
      text: refLabel(run),
    }))
})

function refLabel(run: Run): string {
  return run.tags[0] ?? shortSha(run.commitSha)
}

function valueText(point: Point): string {
  return formatValue(point.value, props.metric.unit)
}

function describe(series: Series, point: Point): string {
  const run = props.runs[point.runIndex]
  const status = presentationOf(point.status).label
  return [
    props.metric.series.length > 1 ? seriesLabel(series) : null,
    run ? `${refLabel(run)}（${formatDateTime(run.measuredAt)}）` : null,
    point.value === null ? status : `${valueText(point)}、${status}`,
    point.threshold ? `合格ライン ${point.threshold}` : null,
  ]
    .filter(Boolean)
    .join('、')
}

function hrefOf(point: Point): string {
  const run = props.runs[point.runIndex]
  return run ? router.resolve({ name: 'release', query: { ref: run.ref } }).href : '#'
}

function open(point: Point): void {
  const run = props.runs[point.runIndex]
  if (run) void router.push({ name: 'release', query: { ref: run.ref } })
}

/** 値の無い点は横軸の上に置く。 */
function pointY(point: Point): number {
  return point.value === null ? BASELINE : y(point.value)
}

const active = ref<{ series: Series; point: Point } | null>(null)
/** ツールチップは点の近くに出す。枠からはみ出さないよう、端の点では寄せる向きと上下を変える。 */
const tooltipStyle = computed(() => {
  if (!active.value) return {}
  const left = x(active.value.point.runIndex)
  const top = pointY(active.value.point)
  const shiftX = left < width.value * 0.25 ? '0%' : left > width.value * 0.75 ? '-100%' : '-50%'
  const shiftY = top < HEIGHT * 0.45 ? '12px' : 'calc(-100% - 12px)'
  return { left: `${left}px`, top: `${top}px`, transform: `translate(${shiftX}, ${shiftY})` }
})

/** 表は計測の新しい順。履歴と同じ並びにして、表とグラフの両方で同じ計測を探しやすくする。 */
const tableRows = computed(() =>
  props.runs
    .map((run, index) => ({
      run,
      cells: props.metric.series.map((s) => s.points.find((p) => p.runIndex === index) ?? null),
    }))
    .filter((row) => row.cells.some((cell) => cell !== null))
    .reverse(),
)

function tickText(value: number): string {
  return formatValue(value, props.metric.unit)
}
</script>

<template>
  <figure class="qg-trend" :aria-labelledby="`trend-${metric.metricId}`">
    <figcaption>
      <h2 :id="`trend-${metric.metricId}`">{{ metric.name }}</h2>
      <p class="qg-muted">{{ metric.summary }}</p>
    </figcaption>

    <ul v-if="lines.length > 1" class="qg-trend__legend" aria-label="凡例">
      <li v-for="line in lines" :key="line.key">
        <svg width="28" height="10" aria-hidden="true">
          <line
            x1="0"
            y1="5"
            x2="28"
            y2="5"
            :stroke="line.color"
            stroke-width="2"
            :stroke-dasharray="line.dash"
          />
        </svg>
        {{ line.label }}
      </li>
      <li v-if="thresholdPath">
        <svg width="28" height="10" aria-hidden="true">
          <line x1="0" y1="5" x2="28" y2="5" class="qg-trend__threshold" />
        </svg>
        合格ライン
      </li>
    </ul>
    <p v-else-if="thresholdPath" class="qg-trend__legend">
      <svg width="28" height="10" aria-hidden="true">
        <line x1="0" y1="5" x2="28" y2="5" class="qg-trend__threshold" />
      </svg>
      合格ライン
    </p>

    <div ref="plot" class="qg-trend__plot">
      <svg
        :width="width"
        :height="HEIGHT"
        :viewBox="`0 0 ${width} ${HEIGHT}`"
        role="group"
        :aria-label="`${metric.name}の推移`"
      >
        <g aria-hidden="true">
          <g v-for="tick in axis.ticks" :key="tick">
            <line
              :x1="LEFT"
              :x2="LEFT + plotWidth"
              :y1="y(tick)"
              :y2="y(tick)"
              :class="tick === 0 ? 'qg-trend__axis' : 'qg-trend__grid'"
            />
            <text :x="LEFT - 8" :y="y(tick)" text-anchor="end" dominant-baseline="middle">
              {{ tickText(tick) }}
            </text>
          </g>
          <text
            v-for="label in xLabels"
            :key="label.key"
            :x="label.x"
            :y="HEIGHT - 8"
            text-anchor="middle"
          >
            {{ label.text }}
          </text>
          <path v-if="thresholdPath" :d="thresholdPath" class="qg-trend__threshold" />
          <g v-for="line in lines" :key="line.key">
            <path
              v-for="(d, i) in line.paths"
              :key="i"
              :d="d"
              class="qg-trend__line"
              :stroke="line.color"
              :stroke-dasharray="line.dash"
            />
          </g>
        </g>

        <g v-for="(line, seriesIndex) in lines" :key="line.key">
          <a
            v-for="point in line.points"
            :key="point.runIndex"
            :href="hrefOf(point)"
            :aria-label="`${describe(metric.series[seriesIndex]!, point)}。この計測の判定を開く`"
            class="qg-trend__point"
            @click.prevent="open(point)"
            @mouseenter="active = { series: metric.series[seriesIndex]!, point }"
            @mouseleave="active = null"
            @focus="active = { series: metric.series[seriesIndex]!, point }"
            @blur="active = null"
          >
            <circle :cx="x(point.runIndex)" :cy="pointY(point)" r="12" class="qg-trend__hit" />
            <circle
              v-if="point.status === 'PASS'"
              :cx="x(point.runIndex)"
              :cy="pointY(point)"
              r="5"
              :data-status="point.status"
            />
            <rect
              v-else-if="point.status === 'FAIL'"
              :x="x(point.runIndex) - 5"
              :y="pointY(point) - 5"
              width="10"
              height="10"
              :data-status="point.status"
            />
            <rect
              v-else-if="point.status === 'ERROR'"
              :x="x(point.runIndex) - 4.5"
              :y="pointY(point) - 4.5"
              width="9"
              height="9"
              :transform="`rotate(45 ${x(point.runIndex)} ${pointY(point)})`"
              :data-status="point.status"
            />
            <line
              v-else
              :x1="x(point.runIndex) - 6"
              :x2="x(point.runIndex) + 6"
              :y1="pointY(point)"
              :y2="pointY(point)"
              :data-status="point.status"
            />
          </a>
        </g>
      </svg>

      <div v-if="active" class="qg-trend__tooltip" :style="tooltipStyle" aria-hidden="true">
        <StatusChip :status="active.point.status" />
        <span v-if="metric.series.length > 1">{{ seriesLabel(active.series) }}</span>
        <strong v-if="active.point.value !== null">{{ valueText(active.point) }}</strong>
        <span v-if="active.point.threshold">合格ライン {{ active.point.threshold }}</span>
        <span v-if="runs[active.point.runIndex]">
          {{ refLabel(runs[active.point.runIndex]!) }}・{{
            formatDateTime(runs[active.point.runIndex]!.measuredAt)
          }}
        </span>
      </div>
    </div>

    <details class="qg-trend__data">
      <summary>数値を表で見る</summary>
      <table class="qg-table">
        <caption class="qg-visually-hidden">
          {{
            metric.name
          }}の計測ごとの値（新しい順）
        </caption>
        <thead>
          <tr>
            <th scope="col">タグ・コミット</th>
            <th scope="col">計測日時</th>
            <th v-for="series in metric.series" :key="seriesLabel(series)" scope="col">
              {{ metric.series.length > 1 ? seriesLabel(series) : '結果' }}
            </th>
            <th scope="col">合格ライン</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in tableRows" :key="row.run.commitSha">
            <th scope="row">
              <RouterLink :to="{ name: 'release', query: { ref: row.run.ref } }">
                {{ refLabel(row.run) }}
              </RouterLink>
            </th>
            <td>{{ formatDateTime(row.run.measuredAt) }}</td>
            <td v-for="(cell, i) in row.cells" :key="i">
              <template v-if="cell">
                <StatusChip :status="cell.status" />
                {{ valueText(cell) }}
              </template>
              <template v-else>—</template>
            </td>
            <td>{{ row.cells.find((cell) => cell?.threshold)?.threshold ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </details>
  </figure>
</template>

<style scoped>
.qg-trend {
  margin: 0 0 1.5rem;
  padding: 1rem 1.25rem;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  break-inside: avoid;
}
.qg-trend h2 {
  font-size: 1.0625rem;
  margin: 0;
}
.qg-trend figcaption p {
  margin: 0.25rem 0 0.5rem;
}
.qg-trend__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1rem;
  list-style: none;
  margin: 0 0 0.25rem;
  padding: 0;
  font-size: 0.8125rem;
  color: var(--text-secondary);
}
.qg-trend__legend li,
p.qg-trend__legend {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}
.qg-trend__plot {
  position: relative;
}
.qg-trend__plot svg {
  display: block;
  overflow: visible;
}
.qg-trend__plot text {
  font-size: 12px;
  fill: var(--text-secondary);
}
.qg-trend__grid {
  stroke: var(--gridline);
  stroke-width: 1;
}
.qg-trend__axis {
  stroke: var(--axis);
  stroke-width: 1;
}
.qg-trend__threshold {
  fill: none;
  stroke: var(--text-secondary);
  stroke-width: 1.5;
  stroke-dasharray: 4 3;
}
.qg-trend__line {
  fill: none;
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.qg-trend__hit {
  fill: transparent;
}
/* 重なった線から点を浮かせるため、面の色で縁取る */
.qg-trend__point [data-status] {
  stroke: var(--surface-1);
  stroke-width: 2;
  fill: var(--status-neutral);
}
.qg-trend__point [data-status='PASS'] {
  fill: var(--status-pass);
}
.qg-trend__point [data-status='FAIL'] {
  fill: var(--status-fail);
}
.qg-trend__point [data-status='ERROR'] {
  fill: var(--status-error);
}
.qg-trend__point line[data-status] {
  stroke: var(--status-neutral);
  stroke-width: 3;
}
.qg-trend__point:focus-visible {
  outline: 2px solid var(--series-1);
}
.qg-trend__tooltip {
  position: absolute;
  display: grid;
  gap: 0.15rem;
  min-width: 10rem;
  padding: 0.5rem 0.65rem;
  background: var(--surface-1);
  border: 1px solid var(--axis);
  border-radius: var(--radius);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  font-size: 0.8125rem;
  color: var(--text-primary);
  pointer-events: none;
  white-space: nowrap;
  z-index: 1;
}
.qg-trend__data {
  margin-top: 0.5rem;
  font-size: 0.875rem;
}
.qg-trend__data summary {
  cursor: pointer;
}
.qg-trend__data td {
  white-space: nowrap;
}
@media print {
  .qg-trend {
    border-color: #999;
  }
  .qg-trend__tooltip {
    display: none;
  }
}
</style>
