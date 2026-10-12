<script setup lang="ts">
import type { components } from '@/api/schema'

type NotJudged = components['schemas']['ReleaseNotJudgedMetric']

/**
 * 判定の対象外にした指標の一覧。結論のすぐ下に置く。
 *
 * 「すべて合格」は判定した指標についての結論であり、判定から外した指標があることを、
 * 開発に携わっていない方にも見落とされないようにする（DD-27）。対象外が無ければ何も出さない。
 */
defineProps<{ items: NotJudged[] }>()

function itemKey(item: NotJudged): string {
  return `${item.metricId}|${item.componentName ?? ''}`
}
</script>

<template>
  <div
    v-if="items.length > 0"
    class="qg-release__scope"
    role="group"
    aria-labelledby="scope-heading"
  >
    <h3 id="scope-heading">
      <i class="pi pi-ban" aria-hidden="true"></i>
      判定の対象外（{{ items.length }} 件）
    </h3>
    <p>次の指標は合否を確かめていません。上の結論は、これらを除いた指標だけで出したものです。</p>
    <ul>
      <li v-for="item in items" :key="itemKey(item)">
        <strong
          >{{ item.name
          }}<template v-if="item.componentName">（{{ item.componentName }}）</template></strong
        >: {{ item.reason }}
      </li>
    </ul>
  </div>
</template>

<style scoped>
.qg-release__scope {
  border: 1px dashed var(--text-secondary);
  border-radius: var(--radius);
  padding: 0.75rem 1rem;
  margin: 0 0 1rem;
  break-inside: avoid;
}
.qg-release__scope h3 {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1rem;
  margin: 0 0 0.25rem;
}
.qg-release__scope p {
  margin: 0 0 0.5rem;
}
.qg-release__scope ul {
  margin: 0;
  padding-left: 1.25rem;
}
.qg-release__scope li + li {
  margin-top: 0.25rem;
}
</style>
