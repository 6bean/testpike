
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import LangSwitch from '@/components/LangSwitch.vue'

function makeStorage(initial: Record<string, string> = {}) {
  const m = new Map<string, string>(Object.entries(initial))
  return {
    getItem: (k: string) => (m.has(k) ? m.get(k)! : null),
    setItem: (k: string, v: string) => void m.set(k, String(v)),
    removeItem: (k: string) => void m.delete(k),
    clear: () => m.clear(),
    key: (i: number) => [...m.keys()][i] ?? null,
    get length() {
      return m.size
    },
  }
}


async function factory(initial: Record<string, string> = {}) {
  vi.resetModules()
  const storage = makeStorage(initial)
  const i18nMod = await import('@/utils/i18n')
  i18nMod.setLocaleStorage(storage as unknown as Storage)
  i18nMod.hydrateLocale()
  const i18n = createI18n({
    legacy: false,
    locale: i18nMod.getLocale(),
    fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': (await import('@/utils/locales/en-US')).default },
  })
  const host = document.createElement('div')
  document.body.appendChild(host)
  const w = mount(LangSwitch, {
    attachTo: host,
    global: { plugins: [ElementPlus, i18n] },
  })
  return { w, storage, i18nMod }
}

afterEach(() => {
  document.documentElement.lang = 'zh-CN'
  vi.unstubAllGlobals()
})

describe('T239 LangSwitch 顶栏语言切换件', () => {
  it('触发器在位：data-test=lang-switch 可见（顶栏入口锚点）', async () => {
    const { w } = await factory()
    expect(w.find('[data-test="lang-switch"]').exists()).toBe(true)
  })

  it('下拉两语言项自描述标签（简体中文/English，禁随 locale 翻译）', async () => {
    const { w } = await factory()
    await w.find('[data-test="lang-switch"]').trigger('click')
    const texts = await vi.waitUntil(
      () => {
        const items = [...document.querySelectorAll('.el-dropdown-menu__item')]
        return items.length >= 2 ? items.map((i) => i.textContent?.trim()) : null
      },
      { timeout: 3000 },
    )
    expect(texts).toContain('简体中文')
    expect(texts).toContain('English')
  })

  it('选 English=setLocale 单件三同步：locale + tp-locale 持久化 + documentElement.lang=en', async () => {
    const { storage, i18nMod } = await factory()
    i18nMod.setLocale('en-US')
    expect(i18nMod.getLocale()).toBe('en-US')
    expect(storage.getItem('tp-locale')).toBe('en-US')
    expect(document.documentElement.lang).toBe('en')
  })

  it('切回中文=往返语义：documentElement.lang=zh-CN（TC-V10-01 往返）', async () => {
    const { i18nMod } = await factory()
    i18nMod.setLocale('en-US')
    expect(document.documentElement.lang).toBe('en')
    i18nMod.setLocale('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')
  })

  it('装载同步：持久化 en-US 时 fresh import + hydrate 即 html lang=en（刷新保留语义）', async () => {
    const { i18nMod } = await factory({ 'tp-locale': 'en-US' })
    expect(i18nMod.getLocale()).toBe('en-US')
    expect(document.documentElement.lang).toBe('en')
  })

  it('真实交互：点触发器→选 English 项→setLocale 单件生效（菜单命令接线）', async () => {
    const { w, i18nMod } = await factory()
    await w.find('[data-test="lang-switch"]').trigger('click')
    const enItem = await vi.waitUntil(
      () => {
        const el = document.querySelector('[data-test="lang-option-en"]')
        return el instanceof HTMLElement ? el : null
      },
      { timeout: 3000 },
    )
    enItem.click()
    await vi.waitUntil(() => i18nMod.getLocale() === 'en-US', { timeout: 3000 })
    expect(document.documentElement.lang).toBe('en')
  })
})
