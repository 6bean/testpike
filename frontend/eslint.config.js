import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslintParser from '@typescript-eslint/parser'
import tseslintPlugin from '@typescript-eslint/eslint-plugin'
import vueParser from 'vue-eslint-parser'

export default [
  { ignores: ['dist/**', 'node_modules/**', '*.config.ts', 'tests/**'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.ts'],
    languageOptions: {
      parser: tseslintParser,
      parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    },
    plugins: { '@typescript-eslint': tseslintPlugin },
    rules: {
      'no-redeclare': 'off',
      '@typescript-eslint/no-redeclare': 'error',
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: { parser: tseslintParser, ecmaVersion: 'latest', sourceType: 'module' },
    },
  },
  {
    files: ['src/**/*.{vue,ts}'],
    plugins: { '@typescript-eslint': tseslintPlugin },
    rules: {
      'no-unused-vars': 'off',
      'vue/multi-word-component-names': 'off',
      'vue/no-unused-vars': 'off',
      'no-undef': 'off',
    },
  },
]
