<script setup lang="ts">
import type { components } from '@/api/schema'

type Condition = components['schemas']['ReleaseCondition']

/**
 * 判定の条件。判定したときに、確かめる範囲を狭めていた設定（計測から外したファイル、対象を限定したコンポーネント、
 * 指定の無いシナリオ・画面）を、結論のすぐ下に「判定の対象外」と並べて示す（DD-28）。
 *
 * 文言はサーバが持つ。conditions が null なのは、判定の条件を記録するようになる前（V004 より前）の計測で、
 * そのときは条件が分からないことを示す（今の設定で補うと、過去の判定の条件を偽ることになる）。
 * 範囲を狭める設定が無ければ（空の一覧）何も出さない。
 */
defineProps<{ conditions: Condition[] | null }>()
</script>

<template>
  <div
    v-if="conditions === null || conditions.length > 0"
    class="qg-release__conditions"
    role="group"
    aria-labelledby="conditions-heading"
  >
    <h3 id="conditions-heading">
      <i class="pi pi-filter" aria-hidden="true"></i>
      判定の条件
    </h3>
    <p v-if="conditions === null">
      記録なし（判定の条件を記録するようになる前の計測です。計測から外したファイルなどの設定は分かりません）
    </p>
    <template v-else>
      <p>
        次の設定で、確かめる範囲を絞って判定しています。上の結論は、この条件のもとでの結果です。
      </p>
      <dl>
        <div v-for="condition in conditions" :key="condition.key" class="qg-release__condition">
          <dt>{{ condition.label }}</dt>
          <dd>
            <span class="qg-release__condition-value">{{ condition.value }}</span>
            <span class="qg-release__condition-note">{{ condition.note }}</span>
            <span class="qg-release__condition-setting"
              >（設定: <code>{{ condition.setting }}</code
              >）</span
            >
          </dd>
        </div>
      </dl>
    </template>
  </div>
</template>

<style scoped>
.qg-release__conditions {
  border: 1px dashed var(--text-secondary);
  border-radius: var(--radius);
  padding: 0.75rem 1rem;
  margin: 0 0 1rem;
  break-inside: avoid;
}
.qg-release__conditions h3 {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1rem;
  margin: 0 0 0.25rem;
}
.qg-release__conditions p {
  margin: 0 0 0.5rem;
}
.qg-release__conditions dl {
  margin: 0;
}
.qg-release__condition + .qg-release__condition {
  margin-top: 0.5rem;
}
.qg-release__conditions dt {
  font-weight: 600;
}
.qg-release__conditions dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.qg-release__condition-value {
  font-weight: 600;
}
.qg-release__condition-note {
  display: block;
}
.qg-release__condition-setting {
  color: var(--text-secondary);
  font-size: 0.875rem;
}
</style>
