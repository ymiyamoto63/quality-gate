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
      '/login': backend,
      '/logout': backend,
      '/badges': backend,
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
    coverage: {
      provider: 'v8',
      // テストが触れていないファイルも分母に含める。include を指定しないと
      // テストを書いた範囲だけの数字になり、実態より良く見える。
      include: ['src/**/*.{ts,vue}'],
      reporter: ['text', 'lcov'],
      reportsDirectory: '../reports/frontend-coverage',
      // 生成物と設定ファイルは計測対象から外す（.quality-gate.yml の exclusions と揃える）
      exclude: ['src/api/schema.d.ts', '**/*.config.ts', 'src/main.ts', 'e2e/**'],
    },
  },
})
