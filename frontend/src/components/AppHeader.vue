<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

const auth = useAuthStore()
const ui = useUiStore()

function cycleTheme() {
  const order = ['system', 'light', 'dark'] as const
  const next = order[(order.indexOf(ui.theme) + 1) % order.length]
  ui.setTheme(next ?? 'system')
}
</script>

<template>
  <header class="qg-header">
    <a class="qg-skip" href="#main">本文へスキップ</a>
    <RouterLink class="qg-brand" to="/">quality-gate</RouterLink>

    <nav aria-label="メインナビゲーション">
      <RouterLink to="/">ダッシュボード</RouterLink>
      <RouterLink to="/waivers">免除</RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/admin/repositories">リポジトリ管理</RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/admin/users">管理</RouterLink>
    </nav>

    <div class="qg-header__right">
      <button
        type="button"
        :aria-label="`テーマを切り替える（現在: ${ui.theme}）`"
        @click="cycleTheme"
      >
        <i class="pi pi-palette" aria-hidden="true" />
      </button>
      <span v-if="auth.user" class="qg-user">
        {{ auth.user.displayName ?? auth.user.githubLogin }}
        <small>{{ auth.user.role }}</small>
      </span>
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

nav {
  display: flex;
  gap: 1rem;
}
nav a {
  color: var(--text-secondary);
  text-decoration: none;
}
nav a.router-link-active {
  color: var(--text-primary);
  font-weight: 600;
}

.qg-header__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 1rem;
}

button {
  background: none;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.35rem 0.6rem;
  color: var(--text-primary);
  cursor: pointer;
}

.qg-user small {
  color: var(--text-muted);
  margin-left: 0.35rem;
}

@media (max-width: 767px) {
  .qg-header {
    gap: 0.75rem;
    padding: 0.75rem 1rem;
  }
}
</style>
