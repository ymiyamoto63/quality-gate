import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import { router } from './router'

import 'primeicons/primeicons.css'
import './styles/tokens.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(PrimeVue, {
  theme: {
    preset: Aura,
    options: {
      // ダークモードは data-theme 属性で切り替える（tokens.css と同じスコープ）
      darkModeSelector: '[data-theme="dark"]',
    },
  },
  locale: {
    accept: 'はい',
    reject: 'いいえ',
    emptyMessage: '該当するデータがありません',
    emptySearchMessage: '検索結果がありません',
  },
})

app.mount('#app')
