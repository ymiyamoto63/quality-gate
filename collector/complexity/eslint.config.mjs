// 収集ランナーが M-07（循環的複雑度）のフロントエンド側の計測に使う ESLint の設定。
//
// 対象リポジトリの ESLint の設定は使わない。対象の設定やプラグインの版で CC の算出が変わると、
// トレンドに段差が出て、比較元（base）との比較も崩れるため（backend の PMD と同じ考え方）。
// complexity ルールの上限を 0 にして「しきい値超過の検出」ではなく「全関数の CC 値の出力」を得る。
// 判定（15 超か、新規・悪化か）は quality-gate 側が行う。
//
// 構文を読むためのパーサだけを使い、complexity 以外のルールは動かさない。
// 解析するファイルと除外は measure.sh が引数で渡す（この設定はどのディレクトリで動かしても同じに効く）。
import tseslint from 'typescript-eslint'
import vueParser from 'vue-eslint-parser'

const complexity = { complexity: ['warn', { max: 0 }] }

export default [
  {
    files: ['**/*.{js,mjs,cjs,jsx}'],
    languageOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    rules: complexity,
  },
  {
    files: ['**/*.{ts,mts,cts,tsx}'],
    languageOptions: { parser: tseslint.parser, ecmaVersion: 'latest', sourceType: 'module' },
    rules: complexity,
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: { parser: tseslint.parser, ecmaVersion: 'latest', sourceType: 'module' },
    },
    rules: complexity,
  },
  // 無効化コメント（eslint-disable complexity）で CC の出力を消されないようにする
  { linterOptions: { noInlineConfig: true, reportUnusedDisableDirectives: 'off' } },
]
