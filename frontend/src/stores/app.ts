import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

const THEME_KEY = 'tp_theme'

export type ThemeMode = 'dark' | 'light'


export const useAppStore = defineStore('app', () => {
  const theme = ref<ThemeMode>((localStorage.getItem(THEME_KEY) as ThemeMode) || 'dark')

  function applyTheme(mode: ThemeMode): void {
    theme.value = mode
    localStorage.setItem(THEME_KEY, mode)
    const html = document.documentElement
    if (mode === 'dark') {
      html.classList.add('dark')
    } else {
      html.classList.remove('dark')
    }
  }

  function toggleTheme(): void {
    applyTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  applyTheme(theme.value)
  watch(theme, (val) => applyTheme(val))

  return { theme, applyTheme, toggleTheme }
})
