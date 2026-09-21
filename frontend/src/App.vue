<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const ui = useUiStore()

onMounted(() => ui.applyTheme(ui.theme))
</script>

<template>
  <AppHeader v-if="!route.meta.public" />
  <main id="main" class="qg-main">
    <RouterView />
  </main>
</template>

<style scoped>
.qg-main {
  max-width: 1440px;
  margin: 0 auto;
  padding: 1.5rem 1.5rem 4rem;
}

@media (max-width: 767px) {
  .qg-main {
    padding: 1rem 1rem 3rem;
  }
}
</style>
