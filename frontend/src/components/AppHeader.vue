<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()

function cycleTheme() {
  const order = ['system', 'light', 'dark'] as const
  const next = order[(order.indexOf(ui.theme) + 1) % order.length]
  ui.setTheme(next ?? 'system')
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <header class="qg-header">
    <a class="qg-skip" href="#main">本文へスキップ</a>
    <RouterLink class="qg-brand" to="/">quality-gate</RouterLink>
    <nav v-if="auth.isAuthenticated" class="qg-header__nav" aria-label="画面">
      <RouterLink :to="{ name: 'release' }">リリース判定</RouterLink>
      <RouterLink :to="{ name: 'trends' }">指標の推移</RouterLink>
    </nav>

    <div class="qg-header__right">
      <button
        type="button"
        :aria-label="`テーマを切り替える（現在: ${ui.theme}）`"
        @click="cycleTheme"
      >
        <i class="pi pi-palette" aria-hidden="true" />
      </button>
      <button v-if="auth.isAuthenticated" type="button" @click="logout">ログアウト</button>
    </div>
  </header>
</template>

<style scoped>
.qg-header {
  display: flex;
  align-items: center;
  gap: 1.5rem;
  padding: 0.75rem 1.5rem;
  background: var(--surface-1);
  border-bottom: 1px solid var(--border);
}

.qg-skip {
  position: absolute;
  left: -9999px;
}
.qg-skip:focus {
  left: 1rem;
  z-index: 10;
}

.qg-brand {
  font-weight: 700;
  color: var(--text-primary);
  text-decoration: none;
}

.qg-header__nav {
  display: flex;
  gap: 1rem;
  font-size: 0.9375rem;
}
/* 今いる画面は下線を外し、太字で示す（RouterLink が aria-current="page" を付ける） */
.qg-header__nav a[aria-current='page'] {
  color: var(--text-primary);
  font-weight: 600;
  text-decoration: none;
}

/* リリース判定や推移を PDF として印刷するとき、ヘッダは要らない */
@media print {
  .qg-header {
    display: none;
  }
}

.qg-header__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

button {
  background: none;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.35rem 0.6rem;
  font: inherit;
  font-size: 0.875rem;
  color: var(--text-primary);
  cursor: pointer;
}

@media (max-width: 767px) {
  .qg-header {
    flex-wrap: wrap;
    gap: 0.5rem 0.75rem;
    padding: 0.75rem 1rem;
  }
  /* 狭い画面では画面の切り替えを 2 行目に置き、ボタンを押しつぶさない */
  .qg-header__nav {
    order: 1;
    flex-basis: 100%;
  }
}
</style>
