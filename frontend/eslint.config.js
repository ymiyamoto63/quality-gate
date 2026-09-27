import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import prettier from 'eslint-config-prettier'

/**
 * `complexity` は 15 を超える関数を警告する。CI は `--max-warnings 0` で実行するため、超えたら失敗する。
 */
export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'src/api/schema.d.ts', 'coverage'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  // 整形は Prettier に任せ、ESLint は書式ルールを持たない（二重管理を避ける）
  prettier,
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser, ecmaVersion: 'latest', sourceType: 'module' },
    },
    rules: {
      complexity: ['warn', { max: 15 }],
      'vue/multi-word-component-names': 'off',
      '@typescript-eslint/no-explicit-any': 'error',
      // 未定義の名前は TypeScript（vue-tsc）が検出する。no-undef はブラウザの
      // グローバル（window / document）を知らず、.vue で誤検出するため切る
      // （typescript-eslint の推奨どおり）
      'no-undef': 'off',
    },
  },
  {
    files: ['**/*.spec.ts', 'e2e/**/*.ts'],
    rules: { '@typescript-eslint/no-explicit-any': 'off' },
  },
)
