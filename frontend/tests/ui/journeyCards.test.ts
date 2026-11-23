
import { readFileSync } from 'node:fs'
import { describe, it, expect, vi } from 'vitest'
import { quickJourneys, computeJourneyProgress, missingHardwareSteps } from '../../src/config/journeys'
import { journeyProbeKeys, probePermissionKey, probeProjectScoped, shouldIssueProbe, runJourneyProbes, type ProbeGate } from '../../src/utils/journeyProbes'
import { allMenuLeaves } from '../../src/config/menuStructure'
import zhCN from '../../src/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '../../src/utils/locales/en-US'

vi.mock('@/api/batch', () => ({ listBatches: vi.fn(async () => ({ list: [], total: 0 })) }))
vi.mock('@/api/case', () => ({ listCases: vi.fn(async () => ({ list: [], total: 0 })) }))

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

describe('T343 包③ 快速旅程数据件', () => {
  it('恰好六条旅程且 id 唯一（方案甲定版五条 + T414 片5 出厂演示编排流程第六条——出厂剧本裁决新增，禁再增减改名）', () => {
    // Ruling change: ships a sixth journey for the factory demo flow.
    expect(quickJourneys.map((j) => j.id)).toEqual(['gen-from-doc', 'run-sample-case', 'batch-schedule', 'reserve-device', 'connect-executor', 'run-flow-demo'])
    for (const j of quickJourneys) {
      expect(j.steps.length, `${j.id} 步骤为空`).toBeGreaterThan(0)
      for (const s of j.steps) {
        expect(s.probeKey, `${j.id} 步骤缺探针键`).toBeTruthy()
      }
    }
  })

  it('每旅程直达 path 必须命中菜单叶子基线（零新 path 铁律；query 参数允许——出厂演示旅程带 view/projectCode/collectionName 深链，路径面仍=叶子）', () => {
    const leafPaths = new Set(allMenuLeaves.map((l) => l.path))
    for (const j of quickJourneys) {
      const leaf = j.targetPath.split('?')[0]
      expect(leafPaths.has(leaf), `旅程 ${j.id} 直达 ${j.targetPath} 不在菜单叶子`).toBe(true)
    }
  })

  it('步骤探针键全部在探针映射册内（禁悬空探测）', () => {
    const declared = new Set(journeyProbeKeys())
    for (const j of quickJourneys) {
      for (const s of j.steps) {
        expect(declared.has(s.probeKey), `探针键 ${s.probeKey} 未注册`).toBe(true)
      }
    }
  })

  it('标题+步骤名 zh/en 双轨命中（禁悬空键，红线 8）', () => {
    for (const j of quickJourneys) {
      expect(lookup(zhCN, j.titleKey), `${j.id} 标题 zh 缺`).toBeTruthy()
      expect(lookup(enUS, j.titleKey), `${j.id} 标题 en 缺`).toBeTruthy()
      for (const s of j.steps) {
        expect(lookup(zhCN, s.labelKey), `${j.id}/${s.probeKey} 步骤名 zh 缺`).toBeTruthy()
        expect(lookup(enUS, s.labelKey), `${j.id}/${s.probeKey} 步骤名 en 缺`).toBeTruthy()
      }
    }
  })

  it('journey 域键集 zh/en 一致（双镜像闸，禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys((zhCN as Record<string, object>).journey ?? {}).sort()
    const enKeys = Object.keys((enUS as Record<string, object>).journey ?? {}).sort()
    expect(zhKeys.length).toBeGreaterThan(10)
    expect(enKeys).toEqual(zhKeys)
  })
})

describe('T343 包③ 进度判定纯函数', () => {
  const j = quickJourneys.find((x) => x.id === 'gen-from-doc')!

  it('全步有数据=全完成，进度 100', () => {
    const outcomes = Object.fromEntries(j.steps.map((s) => [s.probeKey, { ok: true, count: 3 }]))
    const p = computeJourneyProgress(j, outcomes)
    expect(p.doneSteps).toBe(j.steps.length)
    expect(p.percent).toBe(100)
    expect(p.unreachable).toBe(false)
  })

  it('部分有数据=按步数计进度（探针 count=0 不计完成）', () => {
    const first = j.steps[0].probeKey
    const outcomes = Object.fromEntries(j.steps.map((s) => [s.probeKey, { ok: true, count: s.probeKey === first ? 5 : 0 }]))
    const p = computeJourneyProgress(j, outcomes)
    expect(p.doneSteps).toBe(1)
    expect(p.percent).toBe(Math.round(100 / j.steps.length))
  })

  it('任一探针 4xx/异常=不可达步：不静默计完成、整程标不可达（人话非静默）', () => {
    const outcomes = Object.fromEntries(j.steps.map((s) => [s.probeKey, { ok: true, count: 2 }]))
    outcomes[j.steps[1].probeKey] = { ok: false, count: 0 }
    const p = computeJourneyProgress(j, outcomes)
    expect(p.doneSteps).toBe(1)
    expect(p.unreachable).toBe(true)
    expect(p.percent).toBeLessThan(100)
  })

  it('全探针缺结果=零完成不可达（禁假装进度）', () => {
    const p = computeJourneyProgress(j, {})
    expect(p.doneSteps).toBe(0)
    expect(p.unreachable).toBe(false)
    expect(p.percent).toBe(0)
  })
})

describe('T343 包③ 工作台接线+引导浮层单件（源码锚）', () => {
  const dash = readFileSync(new URL('../../src/views/DashboardView.vue', import.meta.url), 'utf8')
  const overlay = readFileSync(new URL('../../src/components/OnboardingOverlay.vue', import.meta.url), 'utf8')

  it('DashboardView 挂旅程卡行（工作台顶部）+首登引导浮层（禁二套，唯一件）', () => {
    expect(dash).toContain('JourneyCardsRow')
    expect(dash).toContain('OnboardingOverlay')
  })

  it('浮层单件消费旅程数据源+关闭记忆键（首登判定=本地记忆，无服务端二套）', () => {
    expect(overlay).toContain('quickJourneys')
    expect(overlay).toContain('ONBOARDING_DISMISS_KEY')
    const main = readFileSync(new URL('../../src/layouts/MainLayout.vue', import.meta.url), 'utf8')
    expect(main).not.toContain('OnboardingOverlay')
  })

  it('卡行渲染进度条+直达跳转+不可达人话（源码锚，活体面=包⑤ E2E）', () => {
    const row = readFileSync(new URL('../../src/components/JourneyCardsRow.vue', import.meta.url), 'utf8')
    expect(row).toContain('el-progress')
    expect(row).toContain('targetPath')
    expect(row).toContain('unreachable')
    expect(row).toContain('runJourneyProbes')
  })
})

describe('T343 收口包B·引导浮层不得阻塞全站交互（族B f9/r2 根因修复）', () => {
  const overlaySrc = readFileSync(new URL('../../src/components/OnboardingOverlay.vue', import.meta.url), 'utf8')

  it('浮层遮罩可穿透：modal=false + modalPenetrable=true（el-overlay is-penetrable=遮罩外点击直达页面）', () => {
    expect(overlaySrc).toContain(':modal="false"')
    expect(overlaySrc).toContain('modal-penetrable')
    expect(overlaySrc).toContain(':lock-scroll="false"')
  })

  it('路由变化即收层（点菜单导航后浮层不滞留压新页面，关闭走 dismiss 记忆=不重复弹）', () => {
    expect(overlaySrc).toContain('watch')
    expect(overlaySrc).toMatch(/route/)
    expect(overlaySrc).toContain('@closed="dismiss"')
  })
})

describe('设备旅程硬件门（无设备画像三要素指路+自动计数通道）', () => {
  const deviceJ = quickJourneys.find((x) => x.id === 'reserve-device')!

  it('硬件标记数据单源：恰好一条旅程的 devices 步标 requireHardware，其余步骤零标记（禁散写探针键名）', () => {
    const hw = quickJourneys.flatMap((j) => j.steps.filter((s) => s.requireHardware).map((s) => `${j.id}/${s.probeKey}`))
    expect(hw).toEqual(['reserve-device/devices'])
  })

  it('missingHardwareSteps 纯函数：探针可达但 0 台=判缺；未跑/异常/有量=不判缺（双向）', () => {
    expect(missingHardwareSteps(deviceJ, { devices: { ok: true, count: 0 }, reservations: { ok: true, count: 0 } })).toEqual(['devices'])
    expect(missingHardwareSteps(deviceJ, { devices: { ok: true, count: 2 }, reservations: { ok: true, count: 0 } })).toEqual([])
    expect(missingHardwareSteps(deviceJ, {})).toEqual([])
    expect(missingHardwareSteps(deviceJ, { devices: { ok: false, count: 0 } })).toEqual([])
    expect(missingHardwareSteps(quickJourneys.find((x) => x.id === 'connect-executor')!, { executors: { ok: true, count: 0 } })).toEqual([])
  })

  it('三要素指路键 zh/en 双轨命中（原因点名+入口路径字面+出路自动计数）', () => {
    for (const k of ['stepNeedDevice', 'deviceGuideReason', 'deviceGuidePath', 'deviceGuideWayOut']) {
      expect(lookup(zhCN, `journey.${k}`), `${k} zh 缺`).toBeTruthy()
      expect(lookup(enUS, `journey.${k}`), `${k} en 缺`).toBeTruthy()
    }
    expect(lookup(zhCN, 'journey.deviceGuidePath')).toContain(lookup(zhCN, 'layout.journeyAppauto'))
    expect(lookup(zhCN, 'journey.deviceGuidePath')).toContain(lookup(zhCN, 'layout.deviceWall'))
    expect(lookup(zhCN, 'journey.deviceGuidePath')).toContain(lookup(zhCN, 'appauto.wall.register'))
    expect(lookup(zhCN, 'journey.deviceGuideWayOut')).not.toMatch(/刷新页面|手动刷新|点击刷新/)
  })

  it('卡行源码锚：硬件标注+指路块+两通道自动计数且卸载配回收（泄漏防护铁律）', () => {
    const row = readFileSync(new URL('../../src/components/JourneyCardsRow.vue', import.meta.url), 'utf8')
    expect(row).toContain('journey-device-guide')
    expect(row).toContain('missingHardwareSteps')
    expect(row).toContain('stepNeedDevice')
    expect(row).toContain('visibilitychange')
    expect(row).toContain('onBeforeUnmount')
    expect(row).toContain('clearInterval')
    expect(row).toContain('removeEventListener')
  })

  it('浮层源码锚：设备旅程静态「需设备接入」前置标注（禁假装未动）', () => {
    const overlay = readFileSync(new URL('../../src/components/OnboardingOverlay.vue', import.meta.url), 'utf8')
    expect(overlay).toContain('stepNeedDevice')
    expect(overlay).toContain('requireHardware')
    expect(overlay).toContain('onboarding-need-device')
  })
})

describe('T343 收口包A·journeyProbes 权限+项目快照双门控（族A 403 噪音根因修复）', () => {
  const gate = (has: boolean, projectIds: number[]): ProbeGate => ({ hasPerm: () => has, projectIds })

  it('探针册权限键绑定=后端闸同码（缺位即族A 403），成员级豁免四键=null', () => {
    expect(probePermissionKey('batches')).toBe('perm:batch:batch-view')
    expect(probePermissionKey('devices')).toBe('perm:project:project-view')
    expect(probePermissionKey('reservations')).toBe('perm:appauto:reservation:view')
    expect(probePermissionKey('executors')).toBe('perm:project:executor-pool')
    expect(probePermissionKey('executorOnline')).toBe('perm:project:executor-pool')
    expect(probePermissionKey('documents')).toBeNull()
    expect(probePermissionKey('cases')).toBeNull()
    expect(probePermissionKey('scheduleFires')).toBeNull()
    expect(probePermissionKey('execTasks')).toBeNull()
  })

  it('项目级标记：执行器族=全局探针（不受项目集限制），其余七项=项目级', () => {
    for (const k of ['executors', 'executorOnline']) expect(probeProjectScoped(k)).toBe(false)
    for (const k of ['documents', 'cases', 'batches', 'scheduleFires', 'devices', 'reservations', 'execTasks']) {
      expect(probeProjectScoped(k), `${k} 应为项目级探针`).toBe(true)
    }
  })

  it('shouldIssueProbe 判定：无权限不发起/豁免键恒发起/项目级 pid 不在集内不发起/全局探针不受集限制', () => {
    expect(shouldIssueProbe('batches', 1, gate(false, [1]))).toBe(false)
    expect(shouldIssueProbe('batches', 1, gate(true, [1]))).toBe(true)
    expect(shouldIssueProbe('cases', 1, gate(false, [1]))).toBe(true)
    expect(shouldIssueProbe('cases', 115377, gate(true, []))).toBe(false)
    expect(shouldIssueProbe('cases', null, gate(true, [1]))).toBe(false)
    expect(shouldIssueProbe('executors', null, gate(true, []))).toBe(true)
    expect(shouldIssueProbe('executors', null, gate(false, []))).toBe(false)
    expect(shouldIssueProbe('nope', 1, gate(true, [1]))).toBe(false)
  })

  it('runJourneyProbes 带门控：未命中权限/陈旧 pid 的探针零 HTTP 外呼（族A 403 根消）', async () => {
    const { listBatches } = await import('@/api/batch')
    const { listCases } = await import('@/api/case')
    const out = await runJourneyProbes(['batches', 'cases'], 115377, gate(true, []))
    expect(listBatches).not.toHaveBeenCalled()
    expect(listCases).not.toHaveBeenCalled()
    expect(out).toEqual({})
  })

  it('缺位结果喂进度件=零完成不可达不亮（禁假装进度口径回归钉）', () => {
    const j = quickJourneys.find((x) => x.id === 'batch-schedule')!
    const p = computeJourneyProgress(j, {})
    expect(p.doneSteps).toBe(0)
    expect(p.unreachable).toBe(false)
    expect(p.percent).toBe(0)
  })
})
