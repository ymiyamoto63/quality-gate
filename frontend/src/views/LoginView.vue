<template>
  <section class="qg-login">
    <h1>quality-gate にログイン</h1>
    <p>GitHub アカウントでログインします。</p>
    <a class="qg-login__button" href="/oauth2/authorization/github">
      <i class="pi pi-github" aria-hidden="true" />
      GitHub でログイン
    </a>
  </section>
</template>

<style scoped>
.qg-login {
  max-width: 24rem;
  margin: 15vh auto;
  text-align: center;
}
.qg-login__button {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.6rem 1.2rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-1);
  color: var(--text-primary);
  text-decoration: none;
}
</style>
