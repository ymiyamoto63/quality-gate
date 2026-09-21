<script setup lang="ts">
import { computed } from 'vue'
import { presentationOf, type MeasurementStatus } from '@/api/status'

/**
 * 判定ステータスのチップ。
 *
 * 色は点・記号・アイコンにのみ使い、文字色には使わない。
 * WARN(#fab219) と ERROR(#ec835a) はライト面でのコントラストが 3:1 を下回るため、
 * ラベルは常にインクトークンで描き、色付きの記号を隣に置いて識別性を担保する。
 */
const props = defineProps<{ status: MeasurementStatus }>()
const presentation = computed(() => presentationOf(props.status))
</script>

<template>
  <span class="qg-status" :data-status="status">
    <i class="pi" :class="presentation.icon" aria-hidden="true" />
    <span class="qg-status__mark" aria-hidden="true">{{ presentation.mark }}</span>
    <span class="qg-status__label">{{ presentation.label }}</span>
  </span>
</template>

<style scoped>
.qg-status {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.875rem;
  color: var(--text-primary);
  white-space: nowrap;
}

.qg-status .pi,
.qg-status__mark {
  color: var(--status-neutral);
  font-size: 0.8rem;
  line-height: 1;
}

.qg-status[data-status='PASS'] .pi,
.qg-status[data-status='PASS'] .qg-status__mark {
  color: var(--status-pass);
}
.qg-status[data-status='WARN'] .pi,
.qg-status[data-status='WARN'] .qg-status__mark {
  color: var(--status-warn);
}
.qg-status[data-status='FAIL'] .pi,
.qg-status[data-status='FAIL'] .qg-status__mark {
  color: var(--status-fail);
}
.qg-status[data-status='ERROR'] .pi,
.qg-status[data-status='ERROR'] .qg-status__mark {
  color: var(--status-error);
}
</style>
