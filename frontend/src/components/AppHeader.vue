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

/* リリース判定を PDF として印刷するとき、ヘッダは要らない */
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
    gap: 0.75rem;
    padding: 0.75rem 1rem;
  }
}
</style>
