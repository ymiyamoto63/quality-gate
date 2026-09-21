<script setup lang="ts">
import { onMounted, onUnmounted, computed } from 'vue'
import { useDashboardStore } from '@/stores/dashboard'
import StatusChip from '@/components/StatusChip.vue'
import { verdictToStatus, type Verdict } from '@/api/status'

interface Card {
  repositoryId: string
  fullName: string
  latestRun: {
    runId: string
    verdict: Verdict | null
    completeness: string | null
    measuredAt: string
  } | null
  openCriticalCount: number
  openHighCount: number
  activeWaiverCount: number
  freshness: {
    lastMeasuredAt: string | null
    lastFullMeasuredAt: string | null
    staleMeasurement: boolean
    staleFullMeasurement: boolean
  }
}

const store = useDashboardStore()
const cards = computed(() => store.repositories as Card[])
const alerts = computed(() => store.alerts as { code: string; message: string }[])

onMounted(async () => {
  await store.load()
  store.startPolling()
})
onUnmounted(() => store.stopPolling())

function formatDateTime(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('ja-JP')
}
</script>

<template>
  <section>
    <h1>ダッシュボード</h1>

    <!-- 計測途絶の警告。判定はサーバが行い、画面は表示するだけ。 -->
    <div v-for="alert in alerts" :key="alert.code" class="qg-alert" role="status">
      <i class="pi pi-exclamation-triangle" aria-hidden="true" />
      {{ alert.message }}
    </div>

    <p v-if="store.state === 'loading'" class="qg-muted">読み込み中…</p>

    <p v-else-if="store.state === 'error'" role="alert">
      {{ store.errorMessage }}
      <button type="button" @click="store.load()">再試行</button>
    </p>

    <!-- 空状態は「何が無いか」と「次に取るべき操作」を示す -->
    <p v-else-if="cards.length === 0" class="qg-empty">
      計測対象のリポジトリがまだ登録されていません。<br />
      管理画面からリポジトリを登録し、発行した Ingest Token を CI に設定してください。
    </p>

    <ul v-else class="qg-cards">
      <li v-for="card in cards" :key="card.repositoryId" class="qg-card">
        <div class="qg-card__head">
          <h2>{{ card.fullName }}</h2>
          <StatusChip :status="verdictToStatus(card.latestRun?.verdict)" />
        </div>

        <p class="qg-muted">
          {{ formatDateTime(card.latestRun?.measuredAt ?? null) }}
          <span v-if="card.latestRun?.completeness === 'PARTIAL'"> · 部分計測</span>
        </p>

        <p>
          重大 {{ card.openCriticalCount }} 件 ・ 高 {{ card.openHighCount }} 件 ・ 免除中
          {{ card.activeWaiverCount }} 件
        </p>

        <!--
          部分計測が続くと判定が実態より良く見えるため、
          最後の完全計測を常に併記する。
        -->
        <p class="qg-muted">
          最後の完全計測: {{ formatDateTime(card.freshness.lastFullMeasuredAt) }}
        </p>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.qg-cards {
  list-style: none;
  padding: 0;
  display: grid;
  gap: 1rem;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
}

.qg-card {
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 1rem 1.25rem;
}

.qg-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.qg-card h2 {
  font-size: 1rem;
  margin: 0;
}

.qg-muted {
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.qg-alert {
  background: var(--surface-1);
  border: 1px solid var(--status-warn);
  border-radius: var(--radius);
  padding: 0.75rem 1rem;
  margin-bottom: 1rem;
}

.qg-empty {
  color: var(--text-secondary);
  line-height: 1.8;
}

@media (max-width: 767px) {
  .qg-cards {
    grid-template-columns: 1fr;
  }
}
</style>
