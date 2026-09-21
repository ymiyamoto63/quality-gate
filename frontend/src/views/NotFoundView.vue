<template>
  <section class="qg-notfound">
    <h1>ページが見つかりません</h1>
    <p>URL が変更されたか、削除された可能性があります。</p>
    <RouterLink to="/">ダッシュボードへ戻る</RouterLink>
  </section>
</template>

<style scoped>
.qg-notfound {
  max-width: 32rem;
  margin: 15vh auto;
  text-align: center;
  line-height: 1.9;
}
</style>
