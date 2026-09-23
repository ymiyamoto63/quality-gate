import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import prettier from 'eslint-config-prettier'

/**
 * `complexity` ルールは M-07（循環的複雑度）の計測元を兼ねる。
 * max を 0 にして「しきい値超過の検出」ではなく「全関数の CC 値の出力」を得る。
 * ベースコミットとの比較は quality-gate 側が行うため、ここでは判定しない。
 * ただし CI のノイズになるため、既定の lint では warn に留める。
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
