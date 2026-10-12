<script setup lang="ts">
import type { components } from '@/api/schema'

type Guide = components['schemas']['ReleaseGuide']

/**
 * 各指標の説明と基準の根拠。文言はサーバが持ち、ここではそのまま並べる。説明が無ければ何も出さない。
 */
defineProps<{ guides: Guide[] }>()
</script>

<template>
  <section v-if="guides.length > 0" aria-labelledby="guides-heading">
    <h2 id="guides-heading">各指標の説明と基準の根拠</h2>
    <p class="qg-muted">
      根拠は既定の合格ラインについての説明です。根拠の種類は「外部基準」（公的・業界の基準がある）、
      「業界の目安」（広く使われる目安）、「チーム判断」（このプロジェクトで決めた値。見直しの対象）の
      3 つです。
    </p>
    <article v-for="guide in guides" :key="guide.metricId" class="qg-release__guide">
      <h3>
        {{ guide.name }}
        <span class="qg-release__basis" :data-basis="guide.basis">{{ guide.basisLabel }}</span>
      </h3>
      <dl>
        <dt>何を見るか</dt>
        <dd>{{ guide.summary }}</dd>
        <dt>基準の根拠</dt>
        <dd>{{ guide.rationale }}</dd>
        <dt>基準を満たさないと</dt>
        <dd>{{ guide.risk }}</dd>
        <dt>計測ツール</dt>
        <dd>{{ guide.tools }}</dd>
      </dl>
      <details>
        <summary>技術的な定義</summary>
        <p>{{ guide.definition }}</p>
      </details>
    </article>
  </section>
</template>

<style scoped>
h2 {
  font-size: 1.0625rem;
  margin-top: 2rem;
}
.qg-release__guide {
  border-top: 1px solid var(--border);
  padding: 0.75rem 0;
  break-inside: avoid;
}
.qg-release__guide h3 {
  font-size: 1rem;
  margin: 0 0 0.5rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.qg-release__basis {
  font-size: 0.75rem;
  font-weight: normal;
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 0.05rem 0.5rem;
  color: var(--text-primary);
}
.qg-release__guide dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.25rem 1rem;
  margin: 0 0 0.5rem;
}
.qg-release__guide dt {
  color: var(--text-secondary);
  font-size: 0.875rem;
}
.qg-release__guide dd {
  margin: 0;
}
.qg-release__guide summary {
  font-size: 0.875rem;
  cursor: pointer;
}
@media (max-width: 767px) {
  .qg-release__guide dl {
    grid-template-columns: 1fr;
  }
}
</style>
