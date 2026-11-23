
import { describe, it, expect, vi, afterEach } from 'vitest'

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


async function freshI18n(initial: Record<string, string> = {}) {
  vi.resetModules()
  const storage = makeStorage(initial)
  const mod = await import('../../src/utils/i18n')
  mod.setLocaleStorage(storage as unknown as Storage)
  mod.hydrateLocale()
  return { mod, storage }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('T238 P1 locale 骨架', () => {
  it('默认 locale=zh-CN（无持久化时）', async () => {
    const { mod } = await freshI18n()
    expect(mod.getLocale()).toBe('zh-CN')
    expect(mod.SUPPORTED_LOCALES).toContain('zh-CN')
    expect(mod.SUPPORTED_LOCALES).toContain('en-US')
  })

  it('setLocale 切换生效 + 写 storage tp-locale 持久化', async () => {
    const { mod, storage } = await freshI18n()
    const ok = mod.setLocale('en-US')
    expect(ok).toBe(true)
    expect(mod.getLocale()).toBe('en-US')
    expect(storage.getItem('tp-locale')).toBe('en-US')
    expect(mod.i18n.global.t('common.confirm')).toBe('Confirm')
  })

  it('初始化从持久化恢复（刷新保留语义）', async () => {
    const { mod } = await freshI18n({ 'tp-locale': 'en-US' })
    expect(mod.getLocale()).toBe('en-US')
    expect(mod.i18n.global.t('layout.dashboard')).toBe('Dashboard')
  })

  it('非法 locale 拒绝：返回 false 且当前语言不变', async () => {
    const { mod } = await freshI18n()
    expect(mod.setLocale('fr-FR' as never)).toBe(false)
    expect(mod.getLocale()).toBe('zh-CN')
  })

  it('en-US 缺失键 fallback zh-CN（P1 仅镜像三域，其余域走回退）', async () => {
    const { mod } = await freshI18n({ 'tp-locale': 'en-US' })
    const { mod: zhMod } = await freshI18n()
    expect(mod.i18n.global.t('case.statusPending')).toBe(
      zhMod.i18n.global.t('case.statusPending'),
    )
  })

  it('errorCodes 映射单点：已知码人话 + 未知码兜底', async () => {
    const { mod } = await freshI18n()
    expect(mod.tErrorCode('40001')).toBe(mod.i18n.global.t('errorCodes.40001'))
    expect(mod.tErrorCode('40001')).not.toBe('errorCodes.40001')
    expect(mod.tErrorCode('99999')).toBe(mod.i18n.global.t('errorCodes.unknown'))
    expect(mod.tErrorCode('NETWORK')).toBe(mod.i18n.global.t('errorCodes.NETWORK'))
  })

  it('EP locale 联动：setLocale 同步 elLocale 单件（main/App 只消费不再地写）', async () => {
    const { mod } = await freshI18n()
    expect(String(mod.elLocale.value.name)).toContain('zh-cn')
    mod.setLocale('en-US')
    expect(String(mod.elLocale.value.name)).toBe('en')
  })

  it('useLocale composable 薄壳复用同一单件（T239 顶栏切换直消费，禁重复实现）', async () => {
    const { mod } = await freshI18n()
    const localeMod = await import('../../src/composables/useLocale')
    const api = localeMod.default()
    expect(api.locale.value).toBe(mod.getLocale())
    api.switchTo('en-US')
    expect(api.locale.value).toBe('en-US')
    expect(mod.getLocale()).toBe('en-US')
  })
})
