<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * ログイン。アカウントは全員で共有する 1 つだけ（ユーザー名とパスワードは管理者から受け取る）。
 * GitHub のアカウントが無くても見られるようにするため、GitHub のログインは使わない。
 */
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const failed = ref(false)
const submitting = ref(false)

async function submit(): Promise<void> {
  submitting.value = true
  failed.value = false
  const ok = await auth.login(username.value, password.value)
  submitting.value = false
  if (!ok) {
    failed.value = true
    password.value = ''
    return
  }
  // 同じサイトの中のパスだけに戻す（外部のサイトへ飛ばさない）
  const next = typeof route.query.next === 'string' ? route.query.next : '/'
  await router.replace(next.startsWith('/') && !next.startsWith('//') ? next : '/')
}
</script>

<template>
  <section class="qg-login">
    <h1>quality-gate にログイン</h1>
    <p>品質基準に照らした、リリースの可否を確かめられます。</p>
    <form @submit.prevent="submit">
      <label for="login-username">ユーザー名</label>
      <input
        id="login-username"
        v-model="username"
        type="text"
        autocomplete="username"
        required
        :aria-invalid="failed"
      />
      <label for="login-password">パスワード</label>
      <input
        id="login-password"
        v-model="password"
        type="password"
        autocomplete="current-password"
        required
        :aria-invalid="failed"
        :aria-describedby="failed ? 'login-error' : undefined"
      />
      <p v-if="failed" id="login-error" role="alert" class="qg-login__error">
        ユーザー名かパスワードが違います。
      </p>
      <button type="submit" class="qg-button" :disabled="submitting">ログイン</button>
    </form>
  </section>
</template>

<style scoped>
.qg-login {
  max-width: 24rem;
  margin: 12vh auto;
}
.qg-login h1 {
  font-size: 1.5rem;
}
form {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  margin-top: 1.5rem;
}
label {
  font-size: 0.875rem;
  margin-top: 0.5rem;
}
input {
  font: inherit;
  padding: 0.45rem 0.6rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-1);
  color: var(--text-primary);
}
button {
  margin-top: 1rem;
  align-self: flex-start;
}
.qg-login__error {
  margin: 0.5rem 0 0;
  font-weight: 600;
}
</style>
