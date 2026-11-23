
import { describe, expect, it } from 'vitest'
import appautoRaw from '@/api/appauto?raw'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const appautoSection = (dict: unknown): Record<string, unknown> =>
  (dict as { appauto: Record<string, unknown> }).appauto

describe('appauto.ts 接管七法镜像闸（admin AppautoTakeoverController 同径）', () => {
  const cases: Array<[string, RegExp]> = [
    ['requestTakeover', /url:\s*'\/appauto\/takeover\/request',\s*method:\s*'post'/],
    ['agreeTakeover', /url:\s*`\/appauto\/takeover\/\$\{id\}\/agree`,\s*method:\s*'post'/],
    ['rejectTakeover', /url:\s*`\/appauto\/takeover\/\$\{id\}\/reject`,\s*method:\s*'post'/],
    ['forceTakeover', /url:\s*`\/appauto\/takeover\/\$\{id\}\/force`,\s*method:\s*'post'/],
    ['endTakeover', /url:\s*`\/appauto\/takeover\/\$\{id\}\/end`,\s*method:\s*'post'/],
    ['getTakeover', /url:\s*`\/appauto\/takeover\/\$\{id\}`,\s*method:\s*'get'/],
    ['enqueueTakeoverInput', /url:\s*`\/appauto\/takeover\/\$\{id\}\/input`,\s*method:\s*'post'/],
  ]
  for (const [name, pattern] of cases) {
    it(`${name}：函数在位且 URL 与 admin 桥逐口对齐`, () => {
      expect(appautoRaw).toMatch(new RegExp(`export function ${name}\\b`))
      expect(appautoRaw).toMatch(pattern)
    })
  }

  it('AppautoTakeover 接口=admin AppautoTakeoverView 13 字段逐一对齐', () => {
    const m = appautoRaw.match(/export interface AppautoTakeover \{([\s\S]*?)\n\}/)
    expect(m, 'AppautoTakeover 接口缺位').not.toBeNull()
    for (const field of ['id', 'deviceId', 'projectId', 'taskId', 'requesterId', 'occupierId',
      'state', 'reason', 'decidedBy', 'decidedAt', 'expiresAt', 'recordingRef', 'createdAt']) {
      expect(m![1], `字段 ${field} 缺位`).toContain(field)
    }
  })

  it('申请入参=TakeoverCreateRequest 三字段（身份物理无位=服务端登录态注入）', () => {
    const m = appautoRaw.match(/export interface TakeoverCreatePayload \{([\s\S]*?)\n\}/)
    expect(m, 'TakeoverCreatePayload 缺位').not.toBeNull()
    expect(m![1]).toContain('deviceId')
    expect(m![1]).toContain('reason')
    expect(m![1]).not.toMatch(/requesterId|occupierId/)
  })

  it('AppautoDevice 尾追 platform 位（V206 列镜像；null=未登记诚实位）', () => {
    expect(appautoRaw).toMatch(/platform\?:\s*string\s*\|\s*null/)
  })
})

describe('i18n 双轨闸：接管键族 zh/en 逐键对账', () => {
  const zh = appautoSection(zhCN)
  const en = appautoSection(enUS)

  const requiredKeys = [
    'takeoverGrayHarmonyNoKeyText',
    'takeoverGrayNoInjection',
    'takeoverGrayPlatformUnknown',
    'takeoverRequestBtn',
    'takeoverReasonPlaceholder',
    'takeoverPendingWait',
    'takeoverAgree',
    'takeoverReject',
    'takeoverForce',
    'takeoverEnd',
    'takeoverActiveHint',
    'takeoverOccupierAsk',
    'takeoverDecidedAgreed',
    'takeoverDecidedRejected',
    'takeoverDecidedForced',
    'takeoverDecidedExpired',
    'takeoverEnded',
    'takeoverTextPlaceholder',
    'takeoverSend',
    'takeoverPermHint',
    'takeoverStageHint',
    'takeoverSensitivePaused',
    'takeoverNoDevice',
  ]

  it('三枚置灰拒因 key 在两语料命中非空串（takeoverInput 判据单源的消费面）', () => {
    for (const k of ['takeoverGrayHarmonyNoKeyText', 'takeoverGrayNoInjection', 'takeoverGrayPlatformUnknown']) {
      expect(typeof zh[k], `zh 缺 ${k}`).toBe('string')
      expect((zh[k] as string).length, `zh ${k} 空串`).toBeGreaterThan(4)
    }
  })

  for (const k of requiredKeys) {
    it(`${k}：zh/en 双写在位且非空`, () => {
      expect(typeof zh[k], `zh-CN 缺 appauto.${k}`).toBe('string')
      expect((zh[k] as string).length, `zh-CN appauto.${k} 空串`).toBeGreaterThan(1)
      expect(typeof en[k], `en-US 缺 appauto.${k}`).toBe('string')
      expect((en[k] as string).length, `en-US appauto.${k} 空串`).toBeGreaterThan(1)
    })
  }

  it('键集不漂移：zh 的 takeover* 键集=en 的 takeover* 键集', () => {
    const keysOf = (obj: Record<string, unknown>) =>
      Object.keys(obj).filter((k) => k.startsWith('takeover')).sort()
    expect(keysOf(en)).toEqual(keysOf(zh))
  })
})
