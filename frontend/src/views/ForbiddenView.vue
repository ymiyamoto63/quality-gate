<template>
  <!--
    「ログインに失敗しました」とは出さない。認証自体は成功しているため、
    利用者が何度やり直しても状況は変わらない。取るべき行動を示す。
  -->
  <section class="qg-forbidden">
    <h1>このシステムを利用する権限がありません</h1>
    <p>
      GitHub の認証には成功しましたが、利用者として登録されていません。<br />
      管理者に登録を依頼してください。
    </p>
    <p class="qg-muted">登録後、あらためてログインしてください。</p>
    <a href="/login">ログイン画面へ戻る</a>
  </section>
</template>

<style scoped>
.qg-forbidden {
  max-width: 32rem;
  margin: 15vh auto;
  text-align: center;
  line-height: 1.9;
}
.qg-muted {
  color: var(--text-secondary);
  font-size: 0.875rem;
}
</style>
