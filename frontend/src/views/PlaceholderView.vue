<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <!-- 基本設計で定義済み・未実装の画面。実装済みと誤認させないよう明示する。 -->
  <section>
    <h1>{{ title }}</h1>
    <p class="qg-muted">
      {{ description ?? 'この画面は設計済みで、実装はこれからです。' }}
    </p>
    <p class="qg-muted">設計は docs/08-screen-design.md を参照してください。</p>
  </section>
</template>

<style scoped>
.qg-muted {
  color: var(--text-secondary);
}
</style>
