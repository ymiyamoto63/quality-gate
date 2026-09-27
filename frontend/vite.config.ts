import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 開発時も本番と同じ「同一オリジン」を再現する。
// ここを本番と変えると、認証まわりだけ開発で再現できない不具合が生まれる。
const backend = { target: 'http://localhost:8080', changeOrigin: false }

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/oauth2': backend,
      // OAuth の折り返し先だけをバックエンドへ送る。'/login' 全体を送ると、
      // SPA のログイン画面（/login）が開発時だけバックエンドに吸われて表示できない。
      // 本番は SPA フォールバックの除外が 'login/' のため /login は SPA に届く。
      '/login/oauth2': backend,
      '/logout': backend,
      '/actuator': backend,
    },
  },
  build: {
    outDir: 'dist',
    // 同梱先は backend/target/classes/static（Maven がコピーする）
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.ts'],
  },
})
