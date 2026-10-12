<script setup lang="ts">
import { computed } from 'vue'
import StatusChip from '@/components/StatusChip.vue'
import ReleaseNotJudged from '@/components/release/ReleaseNotJudged.vue'
import type { components } from '@/api/schema'
import { formatDateTime, shortSha } from '@/api/format'
import type { DisplayStatus } from '@/api/status'

type Report = components['schemas']['ReleaseReportResponse']
type Decision = Report['decision']

/**
 * リリース判定の結論（リリース可 / 不可 / 未計測）。画面の最上部に置き、ここを見れば結論がすぐ分かるようにする。
 *
 * 結論の見出し・理由・合否の件数の下に、判定の範囲（判定の対象外にした指標、既定より緩めた合格ライン）、
 * 判定に使った計測（タグ・コミット・計測日時・比較元）、印刷の操作を並べる。
 * 判定の範囲に関わる表示を足すときは、対象外の一覧（ReleaseNotJudged）と並べてここに置く。
 */
const props = defineProps<{ report: Report }>()

const DECISIONS: Record<Decision, { label: string; status: DisplayStatus }> = {
  RELEASABLE: { label: 'リリース可', status: 'PASS' },
  NOT_RELEASABLE: { label: 'リリース不可', status: 'FAIL' },
  NOT_MEASURED: { label: '未計測', status: 'NOT_MEASURED' },
}

const decision = computed(() => DECISIONS[props.report.decision])
/** 合格ラインを既定より緩めて判定した行の数。結論の近くで知らせ、表で該当の行を探せるようにする。 */
const relaxedCount = computed(
  () => props.report.metrics.filter((row) => row.thresholdRelaxed).length,
)

function print(): void {
  window.print()
}
</script>

<template>
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
      <template v-if="report.counts.failed > 0">、{{ report.counts.failed }} 項目が不合格</template>
    </p>

    <!--
      判定の範囲を結論のすぐ下に示す。「すべて合格」は判定した指標についての結論であり、
      判定から外した指標があることを、開発に携わっていない方にも見落とされないようにする（DD-27）
    -->
    <ReleaseNotJudged :items="report.notJudged" />
    <p v-if="relaxedCount > 0" class="qg-release__scope-note">
      <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>
      合格ラインを既定より緩めて判定した指標が
      {{ relaxedCount }} 件あります（表の「既定より緩い」）。
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
          <a v-if="report.run.ciRunUrl" :href="report.run.ciRunUrl" rel="noopener" target="_blank">
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
</template>

<style scoped>
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
.qg-release__scope-note {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  margin: 0 0 1rem;
}
.qg-release__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin: 1rem 0 0;
}
@media (max-width: 767px) {
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
