<script setup lang="ts">
import StatusChip from '@/components/StatusChip.vue'
import type { components } from '@/api/schema'
import { formatDateTime, shortSha } from '@/api/format'
import { verdictToStatus } from '@/api/status'

type HistoryItem = components['schemas']['ReleaseHistoryItem']

/**
 * 判定の履歴。各行からその計測のリリース判定（?ref=）を開く。印刷には出さない。履歴が無ければ何も出さない。
 *
 * 1 件ごとの表示を足すときは、列（th / td の組）として足し、狭い画面のカード表示用に data-label を付ける。
 */
defineProps<{ items: HistoryItem[] }>()

function refLabel(item: HistoryItem): string {
  return item.tags.length > 0 ? item.tags.join(', ') : shortSha(item.commitSha)
}
</script>

<template>
  <section v-if="items.length > 0" class="qg-no-print" aria-labelledby="history-heading">
    <h2 id="history-heading">判定の履歴</h2>
    <p class="qg-muted">
      指標ごとの値の移り変わりは<RouterLink :to="{ name: 'trends' }">指標の推移</RouterLink
      >で確かめられます。
    </p>
    <table class="qg-table qg-table--stack">
      <thead>
        <tr>
          <th scope="col">判定</th>
          <th scope="col">タグ・コミット</th>
          <th scope="col">計測日時</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.commitSha">
          <td data-label="判定"><StatusChip :status="verdictToStatus(item.verdict)" /></td>
          <th scope="row" data-label="タグ・コミット">
            <RouterLink :to="{ query: { ref: item.ref } }">{{ refLabel(item) }}</RouterLink>
          </th>
          <td data-label="計測日時">{{ formatDateTime(item.measuredAt) }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
h2 {
  font-size: 1.0625rem;
  margin-top: 2rem;
}
@media print {
  .qg-no-print {
    display: none;
  }
}
</style>
