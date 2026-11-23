
import { describe, it, expect, vi, afterEach } from 'vitest'

async function fresh() {
  vi.resetModules()
  const i18nMod = await import('../../src/utils/i18n')
  const notifMod = await import('../../src/api/notification')
  return { i18n: i18nMod, notif: notifMod }
}

function watchIntlifyWarn() {
  const warns: string[] = []
  const spy = vi.spyOn(console, 'warn').mockImplementation((...args: unknown[]) => {
    warns.push(args.map(String).join(' '))
  })
  return { warns, spy }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('T240 P7 事件标签回退链路零 intlify 告警', () => {
  it('eventLabel 已登记码 zh 标签走 notification 域语言包', async () => {
    const { notif } = await fresh()
    expect(notif.eventLabel('AI_GEN_DONE')).toBe('AI 生成完成')
    expect(notif.eventLabel('SCRIPT_FAILED')).toBe('脚本生成失败')
  })

  it('eventLabel 已登记码切 en-US 后走 en 镜像', async () => {
    const { i18n, notif } = await fresh()
    i18n.setLocale('en-US')
    expect(notif.eventLabel('AI_GEN_DONE')).toBe('AI generation completed')
    i18n.setLocale('zh-CN')
  })

  it('未知码回退原文且零 intlify 告警（对照组直喂 t() 必告警=捕获有效）', async () => {
    const { i18n, notif } = await fresh()
    const { warns, spy } = watchIntlifyWarn()
    i18n.i18n.global.t('E2E_N5_HOOK')
    expect(warns.some((w) => w.includes('[intlify]'))).toBe(true)
    warns.length = 0
    expect(notif.eventLabel('E2E_N5_HOOK')).toBe('E2E_N5_HOOK')
    expect(notif.eventLabel('SOME_FUTURE_CODE')).toBe('SOME_FUTURE_CODE')
    expect(notif.eventLabel('')).toBe('')
    expect(warns.filter((w) => w.includes('[intlify]'))).toEqual([])
    spy.mockRestore()
  })

  it('T240 新事件码 MENTION 已登记：中文标签+双语+info 色型', async () => {
    const { i18n, notif } = await fresh()
    expect(notif.eventLabel('MENTION')).toBe('有人提到了你')
    expect(notif.eventTagType('MENTION')).toBe('info')
    i18n.setLocale('en-US')
    expect(notif.eventLabel('MENTION')).toBe('You were mentioned')
    i18n.setLocale('zh-CN')
  })

  it('eventTagType 已登记色型正确 / 未知码回退 info', async () => {
    const { notif } = await fresh()
    expect(notif.eventTagType('AI_GEN_FAILED')).toBe('danger')
    expect(notif.eventTagType('EXECUTOR_ONLINE')).toBe('success')
    expect(notif.eventTagType('UNKNOWN_X')).toBe('info')
  })
})
